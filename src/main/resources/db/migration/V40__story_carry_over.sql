-- [docs/55 Carry-Over] 자유 모드(SANDBOX) 관계 → 스토리(V2) 방 이식 추적 컬럼
-- chat_rooms.carry_over_at        : 이식 시점. null이면 이식 없음. 엔딩 자격 유예(이식 후 유저 10턴) 판정 기준.
-- chat_room_heroines.carried_from_room_id : 이 관계를 이식해 온 자유 모드 방 id. 오프닝 [REUNION] 지시 판정 기준.
ALTER TABLE chat_rooms ADD COLUMN IF NOT EXISTS carry_over_at TIMESTAMP;
ALTER TABLE chat_room_heroines ADD COLUMN IF NOT EXISTS carried_from_room_id BIGINT;
