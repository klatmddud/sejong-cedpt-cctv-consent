-- 한 번이라도 명부에 있었던 수강인지. 명부 업로드 때 명부에 없는 수강을 지울지 정하는 데 쓴다
-- (명부로 확인된 적 없는 수강, 즉 sjpt로만 확인된 수강만 지운다)
ALTER TABLE enrollment ADD COLUMN ever_in_roster BOOLEAN NOT NULL DEFAULT false;

-- V3과 함께 배포되므로 기존 행은 모두 명부에서 온 것이다
UPDATE enrollment SET ever_in_roster = true WHERE in_latest_roster OR NOT in_sjpt;
