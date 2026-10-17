# 스토리 방 다시 열기 — 캐릭터 재선택 경로 복구 (2026-10-18)

## 1. 증상 (종원 보고)

> 프로드의 스토리 모드는 히로인이 정해진 채로 초기화가 불가하다. 스토리 초기화는 대화 기록
> 초기화뿐이고 방을 새로 파는 건 불가능하다.

즉 **첫날 고른 히로인이 영구 고정**이었다. 방 안 설정의 '스토리 초기화'는 진행·기억만 되돌리고
캐스트는 그대로 두며(`resetStory`는 기존 `ChatRoomHeroine` 행을 캡처해 **같은 캐릭터로** 재생성),
로비에서 그 월드를 다시 눌러도 '이야기 이어하기'밖에 없었다.

## 2. 원인 — 백엔드가 아니라 **진입구 소실**

백엔드에는 처음부터 다시 여는 경로가 이미 있다.

- `POST /api/v1/v2/story/rooms` → `StoryV2Service.createOrReuseRoom`
- 같은 월드에 방이 있으면 `ErrorCode.STORY_V2_ROOM_EXISTS` → **409**
  (`GlobalExceptionHandler` 매핑 확인)
- UI가 confirm 후 `overwriteExisting=true`로 재호출하면
  `cascadeResetRoom(room, true)` → `restoreStartLocation` → `applyProfileSnapshot`
  → `reconfigureHeroinesAndPresences(room, **요청 히로인**, …)` → 배경 시딩
  순으로 **요청에 실린 새 캐스트로** 방을 다시 구성한다. UGC 월드 경로(`createOrReuseUgcRoom`)도 대칭.

프론트의 409→confirm→overwrite 처리도 `StoryCreateFlow`에 그대로 살아 있었다.
사라진 것은 **그 플로우로 가는 문**이다.

- 구 로비 `StoryV2LobbyView`의 월드 카드에는 '새로 시작' 버튼이 있었다. 이 화면은
  `106b7a8`(2026-08-06, "데드 UI 복구")까지 `setView("worlds")`로 열렸으나,
  **`0d9f87b`(2026-08-13, 플랫폼형 로비 전환 블록 A)에서 그 setter가 사라져** 렌더 분기만 남은
  죽은 코드가 됐고, Aurora 재설계에서 파일째 삭제됐다(master에 없음).
  → overwrite 경로는 **2026-08-13 이후 프로드 UI에서 도달 불가**였다(그 이전에는 동작했다).
- 현행 `src/pages/lobby/StoryTab.jsx`의 `WorldCard`는 `hasExistingRoom`이면
  1차 버튼을 '이야기 이어하기'로 **치환**만 하고, 생성 플로우로 가는 2차 입구를 두지 않았다.
- `FirstMeetPage`는 샌드박스(`/chat/{roomId}`)로만 간다.

→ 결과적으로 2026-08-13 이후 **overwrite 경로는 프로드 UI에서 도달 불가**였다.

## 3. 조치 — 프론트 전용 (백엔드 무변경)

커밋: `LucidChat-Front` master `33c6163`
`feat(story): add a restart entry so a story room can be reopened with a new cast`

| 파일 | 변경 |
| --- | --- |
| `src/pages/lobby/StoryTab.jsx` | 진행 중 카드 하단에 3차 행동 '처음부터 새로 시작 · 캐릭터부터 다시 고를 수 있어요' 추가. 방에서 넘어온 라우터 state(`restartWorldId`/`restartFromRoomId`)를 **마운트 시 1회** 읽어 생성 플로우를 열고, state는 즉시 `replace`로 비운다. 취소하면 원래 방으로 복귀 |
| `src/pages/ChatPageV2.jsx` | 설정 하단 버튼 '스토리 초기화' → '이야기 다시 시작'. `handleRestartV2`가 `/story`로 월드·방 id를 넘긴다 |
| `src/components/story-v2/StoryV2ResetModal.jsx` | 3번째 선택지 '캐릭터부터 다시 고르기'. 기존 두 초기화 문안에 "등장 캐릭터는 지금 그대로예요"를 명시 — 둘이 **무엇을 안 바꾸는지**가 증상의 핵심이었다 |
| `src/components/story-v2/StoryCreateFlow.jsx` | 409 conflict 모달 문안에 "방금 고른 캐릭터로 1일차부터" 추가 |

### 설계 판단 — 왜 '방 삭제'가 아니라 overwrite인가

`chat_rooms`에 `uk_user_world_mode` / `uk_user_ugc_world_mode`(유저×월드×모드 1방) 제약이 있어
**같은 월드에 방 2개는 불가**다. 선택지는 (a) 기존 overwrite 재사용, (b) DELETE 엔드포인트 신설,
(c) 제약 해제 후 다중 방. 유저가 얻는 결과(새 캐스트로 백지 시작)는 (a)와 (b)가 동일한데
(b)는 신규 테이블·스케줄러·교차 저장소 raw JDBC 삭제를 프로드에 올려야 한다. 프론트 4파일로
끝나는 (a)를 택했다. (c)는 로비·보관함 UI 전면 개편이 따라와야 해 별건.

## 4. 검증

- `npm run build` 통과, `check:tdz` / `check:entry` / `check:tts` / `check:voice-media` 전부 통과
- `eslint` — 변경 4파일 모두 **신규 위반 0**(StoryV2ResetModal의 `motion` 오탐은 기존 baseline)
- 실화면: dev 서버 + `/__design/secondary`에 진행 중 카드·모달을 임시 주입해 데스크톱/375/320px 확인
  (임시 주입분은 커밋 전 되돌림)
- 배포: Vercel Production 반영 확인 — 라이브 번들에 신규 문자열 7종 전부 존재
  (`처음부터 새로 시작`·`캐릭터부터 다시 고르기`·`restartWorldId`·`restartFromRoomId` 등),
  구 문구 `페르소나 처리를 선택하세요` 0건. `/story` 게스트 렌더 정상·콘솔 에러 0

### [확인필요] — 첫 실사용 때 볼 것

overwrite 경로는 **2026-08-13 이후 프로드 UI에서 호출된 적이 없다**(§2). 그 이전엔 동작했고
코드는 라이브 중인 reset 경로와 거의 같은 구조이며 `reconfigureHeroinesAndPresences`/
`initializePresences`는 이전 캐스트를 전제하지 않는다. 다만 그 사이 페르소나 스냅샷(블록 B)·
TTS·배경 시딩이 들어왔고 로컬에 Redis가 없어 종단 실행은 못 했다. 첫 테스트에서 볼 것:

1. 새 히로인으로 방이 다시 구성되는가 (`ChatRoomHeroine` / `CharacterPresence` 교체)
2. 오프닝 씬이 다시 생성되는가 (로그 삭제 → `generateOpeningStream` 멱등 가드 해제)
3. 상태창 8축이 0으로 돌아오는가

## 5. 남은 것

- 같은 월드에서 **기존 방을 보존한 채** 두 번째 방을 파는 건 여전히 불가(제약·UI 양쪽 필요).
- 보관함(`ArchiveTab`)의 스토리 행에는 재시작 입구를 두지 않았다 — 정본 입구는 스토리 탭 카드.
- 공식 월드 생성 경로에는 UGC 경로에 있는 `DataIntegrityViolationException`→409 동시생성 가드가 없다.
