package com.honey.familyspace.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.honey.familyspace.model.Space
import com.honey.familyspace.model.ThemeColor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * 1:1 다중 스페이스 및 초대 코드 관리 저장소
 */
class SpaceRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    /**
     * 무계정 익명 인증 보장 (회원가입 절차 없이 UID 자동 발급)
     */
    suspend fun ensureAnonymousAuth(): String {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            return currentUser.uid
        }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: throw IllegalStateException("익명 인증에 실패했습니다.")
    }

    /**
     * 새 1:1 스페이스 생성 및 6자리 일회성 초대 코드 발급
     *
     * @return Pair<생성된 스페이스 객체, 포맷된 초대코드(예: "H79-K2P")>
     */
    suspend fun createSpace(title: String, themeColor: ThemeColor): Result<Pair<Space, String>> {
        return try {
            val myUid = ensureAnonymousAuth()
            val spaceId = UUID.randomUUID().toString()

            val space = Space(
                id = spaceId,
                title = title.ifBlank { "우리 공간" },
                themeColor = themeColor.name,
                memberUids = listOf(myUid),
                createdBy = myUid,
                createdAt = System.currentTimeMillis()
            )

            // 6자리 난수 초대 코드 생성
            val rawCode = InviteCodeGenerator.generateRawCode()
            val formattedCode = "${rawCode.substring(0, 3)}-${rawCode.substring(3)}"

            val inviteData = hashMapOf(
                "spaceId" to spaceId,
                "createdBy" to myUid,
                "createdAt" to System.currentTimeMillis(),
                // 10분 후 만료 (밀리초)
                "expiresAt" to (System.currentTimeMillis() + 10 * 60 * 1000)
            )

            // Firestore Batch 작업으로 스페이스 및 초대 코드 동시 등록
            val batch = firestore.batch()
            val spaceRef = firestore.collection("spaces").document(spaceId)
            val inviteRef = firestore.collection("invites").document(rawCode)

            batch.set(spaceRef, space)
            batch.set(inviteRef, inviteData)
            batch.commit().await()

            Result.success(Pair(space, formattedCode))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 6자리 초대 코드로 스페이스 참여
     * (성공 즉시 초대 코드를 영구 삭제하여 1회성 사용 및 데이터 격리 보장)
     */
    suspend fun joinSpaceByCode(inputCode: String): Result<Space> {
        return try {
            val myUid = ensureAnonymousAuth()
            val rawCode = InviteCodeGenerator.normalizeCode(inputCode)

            if (!InviteCodeGenerator.isValidCode(rawCode)) {
                return Result.failure(IllegalArgumentException("올바른 6자리 초대 코드를 입력해 주세요."))
            }

            val inviteDoc = firestore.collection("invites").document(rawCode).get().await()
            if (!inviteDoc.exists()) {
                return Result.failure(IllegalStateException("유효하지 않거나 이미 사용 완료된 초대 코드입니다."))
            }

            val expiresAt = inviteDoc.getLong("expiresAt") ?: 0L
            if (System.currentTimeMillis() > expiresAt) {
                // 만료된 코드 삭제
                firestore.collection("invites").document(rawCode).delete().await()
                return Result.failure(IllegalStateException("초대 코드 유효 시간(10분)이 만료되었습니다. 새로 발급받아 주세요."))
            }

            val spaceId = inviteDoc.getString("spaceId")
                ?: return Result.failure(IllegalStateException("스페이스 정보를 찾을 수 없습니다."))

            val spaceRef = firestore.collection("spaces").document(spaceId)

            // 스페이스 멤버에 내 UID 추가 및 초대 코드 영구 삭제 (원자적 트랜잭션)
            firestore.runTransaction { transaction ->
                val spaceSnapshot = transaction.get(spaceRef)
                val members = spaceSnapshot.get("memberUids") as? List<*> ?: emptyList<String>()

                if (members.contains(myUid)) {
                    // 이미 참여 중인 경우
                    return@runTransaction
                }
                if (members.size >= 2) {
                    throw IllegalStateException("이미 2명이 참여 중인 1:1 방입니다.")
                }

                transaction.update(spaceRef, "memberUids", FieldValue.arrayUnion(myUid))
                // 코드 즉시 소멸
                transaction.delete(firestore.collection("invites").document(rawCode))
            }.await()

            // 최종 스페이스 정보 조회
            val updatedSpaceSnapshot = spaceRef.get().await()
            val space = updatedSpaceSnapshot.toObject(Space::class.java)
                ?: return Result.failure(IllegalStateException("스페이스 로드에 실패했습니다."))

            Result.success(space)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 내가 참여 중인 모든 1:1 스페이스 목록 실시간 구독
     */
    fun observeMySpaces(): Flow<List<Space>> = callbackFlow {
        val myUid = auth.currentUser?.uid
        if (myUid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listenerRegistration = firestore.collection("spaces")
            .whereArrayContains("memberUids", myUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val spaces = snapshot?.documents?.mapNotNull { it.toObject(Space::class.java) } ?: emptyList()
                trySend(spaces)
            }

        awaitClose {
            listenerRegistration.remove()
        }
    }
}
