package kr.ac.sejong.cedpt.cctvconsent.admin;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.roster.CourseDeletionService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterParseException;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService;
import kr.ac.sejong.cedpt.cctvconsent.roster.TargetRooms;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.web.WebAttributes;
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

	AdminController(RosterImportService importService, RosterQueryService queries,
			CourseDeletionService courseDeletion, TargetRooms targetRooms) {
		this.importService = importService;
		this.queries = queries;
		this.courseDeletion = courseDeletion;
		this.targetRooms = targetRooms;
	}

	@GetMapping("/login")
	String login(HttpSession session, Model model) {
		Object error = session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		if (error instanceof LockedException ex) {
			model.addAttribute("loginError", ex.getMessage());
		}
		session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		return "admin/login";
	}

	@GetMapping
	String dashboard(Model model) {
		model.addAttribute("semesters", queries.semesters());
		model.addAttribute("uploads", queries.recentUploads());
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
			redirect.addFlashAttribute("result", importService.importRoster(roster, List.of()));
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
			redirect.addFlashAttribute("error", "확인할 업로드가 없습니다. 명부를 다시 올려 주세요.");
			return "redirect:/admin/roster";
		}
		try {
			redirect.addFlashAttribute("result", importService.importRoster(pending.roster(), pending.deletions(),
					pending.lastUploadId()));
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

	/** 브라우저가 보낸 경로를 떼고 DB 칼럼 길이(255)에 맞춘다. */
	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

}
