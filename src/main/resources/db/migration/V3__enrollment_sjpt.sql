-- 학생 로그인 때 sjpt(일반행정정보)에서 수강을 확인했는지. 수강 중 = in_latest_roster OR in_sjpt
ALTER TABLE enrollment ADD COLUMN in_sjpt BOOLEAN NOT NULL DEFAULT false;

-- 명부 업로드 때 명부에 없어 삭제한 sjpt 확인 수강 수와, 그중 동의서가 있던 수 (동의서도 함께 삭제됨)
ALTER TABLE roster_upload ADD COLUMN deleted_count INT NOT NULL DEFAULT 0;
ALTER TABLE roster_upload ADD COLUMN deleted_consent_count INT NOT NULL DEFAULT 0;
