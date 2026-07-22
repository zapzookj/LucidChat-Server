-- V13 · 캐릭터 프로필 뷰 + 세계관 사후 편집 (additive only, 전문 멱등 — 2단계 부팅 대응)

-- characters: 몰입형 신상 4종 + 무드 태그 (UGC=Stage0 산출, 공식=시드 수기 입력 — 미입력은 "기록 없음")
ALTER TABLE characters ADD COLUMN IF NOT EXISTS height    VARCHAR(30);
ALTER TABLE characters ADD COLUMN IF NOT EXISTS likes     VARCHAR(200);
ALTER TABLE characters ADD COLUMN IF NOT EXISTS dislikes  VARCHAR(200);
ALTER TABLE characters ADD COLUMN IF NOT EXISTS hobby     VARCHAR(200);
ALTER TABLE characters ADD COLUMN IF NOT EXISTS mood_tags VARCHAR(200);

-- ugc_world_locations: 사후 장소 추가의 생성 상태 (READY/GENERATING/FAILED — String 상수, enum check 없음)
ALTER TABLE ugc_world_locations ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'READY';
