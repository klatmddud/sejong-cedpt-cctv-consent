package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 관리자가 고른 과목들의 동의서를 내려받을 파일로 만든다. 과목 하나는 PDF 하나, 여러 과목은 과목별 PDF를 담은 ZIP이다.
 * 과목 PDF에는 "시작" 표지, 받은 동의서(학번 순), "끝" 표지 순서로 넣고, ZIP 안의 과목은 관리자가 고른 순서(화면 순서)다.
 * 동의서는 지금 수강 중인 학생(최근 명부 또는 sjpt에서 확인됨)의 것만 넣어 화면의 학생, 미제출 수와 맞춘다.
 *
 * 서버 heap이 작아(384MB) 여러 과목을 PDF 하나로 합치지 않는다. PDFBox는 합치는 동안 내용을 heap에 복사해 한 학기 전체를
 * 합치면 200MB를 넘게 쓴다. 과목 PDF를 하나씩 만들어 바로 응답(ZIP)으로 흘려보내고 지우므로, 메모리와 임시 파일은
 * 가장 큰 과목 하나만큼만 쓴다. 그래도 무거운 작업이라 서버 전체에서 한 번에 하나만 만든다.
 */
@Service
public class ConsentBundleService {

	private record CourseRow(long id, String code, String name, String room, String professor) {
	}

	/**
	 * 내려받을 파일 하나. {@link #writeTo}로 응답에 쓰고, 다 쓰면 {@link #close()}로 임시 파일을 지우고 차례를 넘긴다.
	 */
	public final class Download implements AutoCloseable {

		private final String semester;
		private final List<CourseRow> courses;
		private Path directory;
		private int consentCount;
		private boolean closed;

		private Download(String semester, List<CourseRow> courses) {
			this.semester = semester;
			this.courses = courses;
		}

		public boolean zip() {
			return courses.size() > 1;
		}

		/** @return application/zip 또는 application/pdf */
		public String contentType() {
			return zip() ? "application/zip" : "application/pdf";
		}

		public String fileName() {
			if (zip()) {
				return "CCTV동의서_" + semester + "_" + courses.size() + "과목.zip";
			}
			CourseRow course = courses.get(0);
			return "CCTV동의서_" + semester + "_" + course.code() + "_" + course.name() + ".pdf";
		}

		public int courseCount() {
			return courses.size();
		}

		/** @return 넣은 동의서 수. {@link #writeTo}가 끝난 뒤에 맞는 값이다 */
		public int consentCount() {
			return consentCount;
		}

		/** 임시 디렉터리. 테스트에서 지워졌는지 확인한다 */
		Path directory() {
			return directory;
		}

		/** PDF 또는 ZIP을 out에 쓴다. out은 닫지 않는다. */
		public void writeTo(OutputStream out) throws IOException {
			directory = Files.createTempDirectory(TEMP_PREFIX);
			if (!zip()) {
				Path file = directory.resolve("course.pdf");
				consentCount += writeCourse(courses.get(0), directory, file);
				Files.copy(file, out);
				return;
			}
			ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
			// PDF는 이미 압축돼 있어 다시 압축해도 거의 줄지 않는다. 약한 서버 CPU를 아끼려고 압축하지 않고 담는다
			zip.setLevel(Deflater.NO_COMPRESSION);
			for (int index = 0; index < courses.size(); index++) {
				CourseRow course = courses.get(index);
				Path file = directory.resolve("course-" + index + ".pdf");
				consentCount += writeCourse(course, directory, file);
				zip.putNextEntry(new ZipEntry(entryName(index, course)));
				Files.copy(file, zip);
				zip.closeEntry();
				Files.delete(file);
			}
			zip.finish();
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			deleteRecursively(directory);
			running.release();
		}

	}

	/** 고른 과목이 이 학기에 없다. */
	public static class NoCourseException extends RuntimeException {

		NoCourseException() {
			super("내려받을 과목을 고르세요.");
		}

	}

	/** 다른 다운로드 파일을 만드는 중이다. */
	public static class BusyException extends RuntimeException {

		BusyException() {
			super("다른 동의서 파일을 만드는 중입니다. 잠시 후 다시 받으세요.");
		}

	}

	private static final String TEMP_PREFIX = "consent-bundle-";

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate named;
	private final ConsentPdfRenderer renderer;
	private final Semaphore running = new Semaphore(1);

	public ConsentBundleService(JdbcTemplate jdbc, ConsentPdfRenderer renderer) {
		this.jdbc = jdbc;
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.renderer = renderer;
	}

	/**
	 * 서버가 묶는 도중 비정상 종료되면 개인정보가 담긴 임시 디렉터리가 남는다. 기동할 때 지운다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void removeLeftovers() {
		Path temp = Path.of(System.getProperty("java.io.tmpdir"));
		try (Stream<Path> leftovers = Files.list(temp)) {
			leftovers.filter(path -> path.getFileName().toString().startsWith(TEMP_PREFIX))
					.forEach(ConsentBundleService::deleteRecursively);
		}
		catch (IOException ex) {
			// 지우지 못하면 OS의 임시 파일 정리에 맡긴다
		}
	}

	/**
	 * 내려받을 파일을 준비한다. 돌려받은 Download는 반드시 닫아야 다음 다운로드가 가능하다.
	 *
	 * @param codes 학수번호, 이 순서로 담는다. 겹치면 처음 것만 쓰고, 이 학기에 없는 것은 무시한다.
	 * @throws NoCourseException 고른 과목 중 이 학기에 있는 것이 없음
	 * @throws BusyException 다른 다운로드 파일을 만드는 중
	 */
	public Download open(long semesterId, Collection<String> codes) {
		List<String> order = codes.stream().distinct().toList();
		List<CourseRow> courses = order.isEmpty() ? List.of() : named.query("""
				select id, code, name, room, professor from course
				where semester_id = :semesterId and code in (:codes)""",
				Map.of("semesterId", semesterId, "codes", order),
				(rs, row) -> new CourseRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
						rs.getString("room"), rs.getString("professor")))
				.stream()
				.sorted(Comparator.comparingInt(course -> order.indexOf(course.code())))
				.toList();
		if (courses.isEmpty()) {
			throw new NoCourseException();
		}
		String semester = jdbc.queryForObject("select year || '-' || term || '학기' from semester where id = ?",
				String.class, semesterId);
		if (!running.tryAcquire()) {
			throw new BusyException();
		}
		return new Download(semester, courses);
	}

	/** 과목 하나: 시작 표지, 동의서, 끝 표지를 courseFile로 합친다. @return 동의서 수 */
	private int writeCourse(CourseRow course, Path directory, Path courseFile) throws IOException {
		Path pieces = Files.createDirectory(directory.resolve("pieces-" + course.id()));
		List<Path> files = new ArrayList<>();
		files.add(Files.write(pieces.resolve("start.pdf"), cover("시작", course)));
		List<Long> consentIds = jdbc.queryForList("""
				select s.id from consent s
				join enrollment e on e.id = s.enrollment_id
				where e.course_id = ? and (e.in_latest_roster or e.in_sjpt)
				order by e.student_id""", Long.class, course.id());
		// PDF는 한 건씩 읽는다. 한 쿼리로 읽으면 드라이버가 결과 전체를 메모리에 올린다
		for (Long consentId : consentIds) {
			byte[] pdf = jdbc.queryForObject("select pdf from consent_document where consent_id = ?", byte[].class,
					consentId);
			files.add(Files.write(pieces.resolve("consent-" + files.size() + ".pdf"), pdf));
		}
		int consents = consentIds.size();
		files.add(Files.write(pieces.resolve("end.pdf"), cover("끝", course)));
		merge(files, courseFile);
		deleteRecursively(pieces);
		return consents;
	}

	private byte[] cover(String mark, CourseRow course) {
		return renderer.cover(mark, course.name(), course.code(), course.room(), course.professor());
	}

	private static void merge(List<Path> sources, Path target) throws IOException {
		PDFMergerUtility merger = new PDFMergerUtility();
		for (Path source : sources) {
			merger.addSource(source.toFile());
		}
		merger.setDestinationFileName(target.toString());
		// 합치는 동안 쓰는 버퍼도 메모리 대신 임시 파일에 둔다
		merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
	}

	/** ZIP 안의 파일 이름. 앞 번호로 고른 순서를 지키고, 파일 이름에 못 쓰는 글자는 _로 바꾼다 */
	private static String entryName(int index, CourseRow course) {
		String name = "%02d_%s_%s.pdf".formatted(index + 1, course.code(), course.name());
		return name.replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private static void deleteRecursively(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException ex) {
			// 임시 파일은 OS가 정리하므로 지우지 못해도 다운로드는 실패시키지 않는다
		}
	}

}
