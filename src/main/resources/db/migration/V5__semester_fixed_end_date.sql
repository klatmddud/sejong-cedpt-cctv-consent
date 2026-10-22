-- 학기 종료일을 관리자가 입력하지 않고 학기로 정한다: 1학기 6월 30일, 2학기 12월 31일 (Semester.endsOn)
-- 이미 있는 학기도 같은 규칙으로 맞추고, 파기 예정일은 종료일 1년 뒤로 다시 계산한다
UPDATE semester
SET ends_on  = CASE term WHEN '1' THEN make_date(year, 6, 30) ELSE make_date(year, 12, 31) END,
    purge_on = CASE term WHEN '1' THEN make_date(year + 1, 6, 30) ELSE make_date(year + 1, 12, 31) END
WHERE term IN ('1', '2');
