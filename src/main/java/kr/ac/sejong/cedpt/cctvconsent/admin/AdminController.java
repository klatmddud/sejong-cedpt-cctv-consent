package kr.ac.sejong.cedpt.cctvconsent.admin;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.consent.ConsentBundleService;
import kr.ac.sejong.cedpt.cctvconsent.roster.CourseDeletionService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterParseException;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService;
import kr.ac.sejong.cedpt.cctvconsent.roster.SemesterPurge;
import kr.ac.sejong.cedpt.cctvconsent.roster.TargetRooms;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.WebUtils;

@Controller
@RequestMapping("/admin")
class AdminController {

	/**
	 * 동의서 삭제 확인을 기다리는 업로드. 확인하면 같은 명부로 다시 반영한다.
	 * 세션에는 원본 파일(수 MB) 대신 읽고 합친 명부를 둔다.
	 *
	 * @param lastUploadId 확인 화면을 보여 줄 때 이 학기에 마지막으로 반영된 업로드(그 뒤 다른 명부가 반영되면 거절)
	 */
	record PendingRoster(RosterImportService.ParsedRoster roster, List<RosterImportService.ConsentDeletion> deletions,
			Long lastUploadId) implements Serializable {

		PendingRoster withConfirmation(RosterImportService.ConfirmationRequiredException ex) {
			return new PendingRoster(roster, ex.getDeletions(), ex.getLastUploadId());
		}

	}

	private static final String PENDING_ROSTER = "pendingRoster";

	private final RosterImportService importService;
	private final RosterQueryService queries;
	private final CourseDeletionService courseDeletion;
	private final TargetRooms targetRooms;
	private final ConsentBundleService bundles;
	private final SemesterPurge semesterPurge;

	AdminController(RosterImportService importService, RosterQueryService queries,
			CourseDeletionService courseDeletion, TargetRooms targetRooms, ConsentBundleService bundles,
			SemesterPurge semesterPurge) {
		this.importService = importService;
		this.queries = queries;
		this.courseDeletion = courseDeletion;
		this.targetRooms = targetRooms;
		this.bundles = bundles;
		this.semesterPurge = semesterPurge;
	}

	@GetMapping
	String dashboard(Model model) {
		List<RosterQueryService.SemesterSummary> semesters = queries.semesters();
		model.addAttribute("semesters", semesters);
		model.addAttribute("uploads", queries.recentUploads());
		// 요약은 가장 최근 학기(학기 목록 첫 줄)를 보여 준다
		if (!semesters.isEmpty()) {
			RosterQueryService.SemesterSummary current = semesters.get(0);
			model.addAttribute("dashboard", DashboardView.of(current, queries.courses(current.id()),
					targetRooms::matching, queries.sjptOnlyEnrollments(current.id()),
					queries.lastRosterUploadAt(current.id()).orElse(null)));
		}
		return "admin/dashboard";
	}

	/**
	 * @param view list(강의실별 목록) 또는 timetable(강의실 하나의 주간 시간표)
	 * @param room 시간표로 볼 강의실
	 */
	@GetMapping("/semesters/{id}")
	String semester(@PathVariable long id, @RequestParam(defaultValue = "list") String view,
			@RequestParam(required = false) String room, Model model) {
		model.addAttribute("semester", queries.semester(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
		model.addAttribute("timetable", "timetable".equals(view));
		model.addAttribute("page", SemesterPage.of(queries.courses(id), room, targetRooms::matching));
		return "admin/semester";
	}

	/**
	 * 학기 하나의 모든 데이터(과목, 수강, 동의서, 업로드 이력)를 지운다. "2026-2학기 초기화"처럼 학기 이름과 "초기화"를
	 * 정확히 입력해야 지운다.
	 */
	@PostMapping("/semesters/{id}/reset")
	String resetSemester(@PathVariable long id, @RequestParam String confirmText, RedirectAttributes redirect) {
		try {
			SemesterPurge.Reset reset = semesterPurge.reset(id, confirmText);
			AdminAudit.log("학기 초기화: {} (학기 {}), 과목 {}개, 수강 {}건, 동의서 {}건", reset.label(), id,
					reset.courseCount(), reset.enrollmentCount(), reset.consentCount());
			redirect.addFlashAttribute("notice", "%s를 초기화했습니다 (과목 %d개, 수강 %d건, 동의서 %d건)."
					.formatted(reset.label(), reset.courseCount(), reset.enrollmentCount(), reset.consentCount()));
		}
		catch (SemesterPurge.ResetRefusedException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/admin";
	}

	/**
	 * 고른 과목들의 동의서를 PDF 하나로 내려준다. 과목마다 시작, 끝 표지가 들어간다.
	 * 파일이 클 수 있어 임시 파일로 만든 뒤 그대로 흘려보내고 지운다.
	 *
	 * @return 고른 과목이 없을 때만 학기 화면으로 돌려보낸다. 내려줬으면 null(응답을 직접 씀)
	 */
	@PostMapping("/semesters/{id}/consents/download")
	String downloadConsents(@PathVariable long id, @RequestParam(name = "codes", required = false) List<String> codes,
			HttpServletResponse response, RedirectAttributes redirect) throws IOException {
		try (ConsentBundleService.Bundle bundle = bundles.bundle(id, codes == null ? List.of() : codes)) {
			response.setContentType(MediaType.APPLICATION_PDF_VALUE);
			response.setContentLengthLong(Files.size(bundle.file()));
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(bundle.fileName(), StandardCharsets.UTF_8).build().toString());
			AdminAudit.log("동의서 일괄 다운로드: 학기 {}, 과목 {}개, 동의서 {}건", id, bundle.courseCount(),
					bundle.consentCount());
			Files.copy(bundle.file(), response.getOutputStream());
			return null;
		}
		catch (ConsentBundleService.NoCourseException ex) {
			redirect.addAttribute("id", id);
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/admin/semesters/{id}";
		}
	}

	/**
	 * 과목과 그 수강, 동의서를 지운다. 관리자가 학수번호를 직접 입력하고, 확인 화면에서 본 동의서 수가 지금과 같아야 지운다.
	 *
	 * @param view, room 지운 뒤 돌아갈 화면
	 */
	@PostMapping("/semesters/{id}/courses/delete")
	String deleteCourse(@PathVariable long id, @RequestParam String code, @RequestParam String confirmCode,
			@RequestParam int consentCount, @RequestParam(defaultValue = "list") String view,
			@RequestParam(required = false) String room, RedirectAttributes redirect) {
		redirect.addAttribute("id", id);
		redirect.addAttribute("view", view);
		if (room != null) {
			redirect.addAttribute("room", room);
		}
		if (!code.equals(confirmCode.strip())) {
			redirect.addFlashAttribute("error", "입력한 학수번호가 " + code + "와(과) 다릅니다. 과목을 지우지 않았습니다.");
			return "redirect:/admin/semesters/{id}";
		}
		try {
			CourseDeletionService.Deleted deleted = courseDeletion.delete(id, code, consentCount);
			AdminAudit.log("과목 삭제: 학기 {}, 과목 {}, 수강 {}건, 동의서 {}건", id, deleted.code(),
					deleted.enrollmentCount(), deleted.consentCount());
			redirect.addFlashAttribute("notice", "%s %s 과목을 지웠습니다 (수강 %d건, 동의서 %d건)."
					.formatted(deleted.name(), deleted.code(), deleted.enrollmentCount(), deleted.consentCount()));
		}
		catch (CourseDeletionService.CourseNotFoundException | CourseDeletionService.ConsentCountChangedException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/admin/semesters/{id}";
	}

	@GetMapping("/roster")
	String rosterForm(HttpSession session, Model model) {
		model.addAttribute("pending", session.getAttribute(PENDING_ROSTER));
		model.addAttribute("uploads", queries.recentUploads());
		return "admin/roster";
	}

	@PostMapping("/roster")
	String uploadRoster(@RequestParam("file") List<MultipartFile> uploaded, HttpSession session,
			RedirectAttributes redirect) {
		session.removeAttribute(PENDING_ROSTER);
		List<RosterImportService.UploadedFile> files = new ArrayList<>();
		for (MultipartFile file : uploaded) {
			String fileName = fileName(file);
			if (file.isEmpty() || !fileName.toLowerCase().endsWith(".xlsx")) {
				redirect.addFlashAttribute("error", "수강명부 xlsx 파일을 선택하세요.");
				return "redirect:/admin/roster";
			}
			try {
				files.add(new RosterImportService.UploadedFile(fileName, file.getBytes()));
			}
			catch (IOException ex) {
				redirect.addFlashAttribute("error", "업로드한 파일을 읽을 수 없습니다.");
				return "redirect:/admin/roster";
			}
		}
		if (files.isEmpty()) {
			redirect.addFlashAttribute("error", "수강명부 xlsx 파일을 선택하세요.");
			return "redirect:/admin/roster";
		}
		RosterImportService.ParsedRoster roster;
		try {
			roster = importService.read(files);
		}
		catch (RosterParseException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/admin/roster";
		}
		try {
			redirect.addFlashAttribute("result", audited(roster, importService.importRoster(roster, List.of())));
		}
		catch (RosterImportService.ConfirmationRequiredException ex) {
			session.setAttribute(PENDING_ROSTER,
					new PendingRoster(roster, ex.getDeletions(), ex.getLastUploadId()));
		}
		catch (RosterParseException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/admin/roster";
	}

	/**
	 * 명부에 없는 수강의 동의서 삭제를 확인하고 보류한 명부를 반영한다.
	 * 확인 화면 이후 삭제 대상 동의서가 늘었으면 반영하지 않고 새 목록으로 다시 확인받는다.
	 * 그 사이 같은 학기에 다른 명부가 반영됐으면(다른 관리자, 같은 확인을 두 번 보냄) 반영하지 않는다.
	 */
	@PostMapping("/roster/confirm")
	String confirmRoster(HttpSession session, RedirectAttributes redirect) {
		// 확인을 두 번 보내도 한 번만 반영되도록 보류한 명부를 먼저 꺼낸다
		PendingRoster pending;
		synchronized (WebUtils.getSessionMutex(session)) {
			pending = session.getAttribute(PENDING_ROSTER) instanceof PendingRoster held ? held : null;
			session.removeAttribute(PENDING_ROSTER);
		}
		if (pending == null) {
			// 확인을 두 번 보내면 두 번째가 여기로 온다. 첫 번째가 반영했을 수 있으므로 다시 올리라고만 하지 않는다
			redirect.addFlashAttribute("error",
					"확인할 업로드가 없습니다. 방금 반영했다면 아래 최근 업로드에서 확인하고, 없으면 명부를 다시 올려 주세요.");
			return "redirect:/admin/roster";
		}
		try {
			redirect.addFlashAttribute("result", audited(pending.roster(),
					importService.importRoster(pending.roster(), pending.deletions(), pending.lastUploadId())));
		}
		catch (RosterImportService.ConfirmationRequiredException ex) {
			session.setAttribute(PENDING_ROSTER, pending.withConfirmation(ex));
			redirect.addFlashAttribute("error", "확인하는 사이 삭제될 동의서가 바뀌었습니다. 목록을 다시 확인하세요.");
		}
		catch (RosterImportService.StaleRosterException | RosterParseException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		catch (RuntimeException ex) {
			// 예상하지 못한 오류면 다시 확인할 수 있도록 되돌려 놓는다
			session.setAttribute(PENDING_ROSTER, pending);
			throw ex;
		}
		return "redirect:/admin/roster";
	}

	@PostMapping("/roster/cancel")
	String cancelRoster(HttpSession session) {
		session.removeAttribute(PENDING_ROSTER);
		return "redirect:/admin/roster";
	}

	private static RosterImportService.Result audited(RosterImportService.ParsedRoster roster,
			RosterImportService.Result result) {
		AdminAudit.log("명부 반영: {}-{}학기, 파일 [{}], 수업 {}개, 추가 {}건, 제외 {}건, 삭제 {}건(동의서 {}건)",
				result.year(), result.term(), roster.fileNames(), result.courseCount(), result.addedCount(),
				result.removedCount(), result.deletedCount(), result.deletedConsentCount());
		return result;
	}

	/** 브라우저가 보낸 경로를 떼고 DB 칼럼 길이(255)에 맞춘다. */
	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

}
