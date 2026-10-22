package kr.ac.sejong.cedpt.cctvconsent.admin;

import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterParseException;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService;
import org.springframework.format.annotation.DateTimeFormat;
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

@Controller
@RequestMapping("/admin")
class AdminController {

	/** 동의서 삭제 확인을 기다리는 업로드. 확인하면 같은 파일로 다시 반영한다. */
	record PendingRoster(String fileName, byte[] content, LocalDate endsOn,
			List<RosterImportService.ConsentDeletion> deletions) implements Serializable {
	}

	private static final String PENDING_ROSTER = "pendingRoster";

	private final RosterImportService importService;
	private final RosterQueryService queries;

	AdminController(RosterImportService importService, RosterQueryService queries) {
		this.importService = importService;
		this.queries = queries;
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

	@GetMapping("/semesters/{id}")
	String semester(@PathVariable long id, Model model) {
		model.addAttribute("semester", queries.semester(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
		model.addAttribute("courses", queries.courses(id));
		return "admin/semester";
	}

	@GetMapping("/roster")
	String rosterForm(HttpSession session, Model model) {
		model.addAttribute("pending", session.getAttribute(PENDING_ROSTER));
		model.addAttribute("uploads", queries.recentUploads());
		return "admin/roster";
	}

	@PostMapping("/roster")
	String uploadRoster(@RequestParam("file") MultipartFile file,
			@RequestParam("endsOn") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
			HttpSession session, RedirectAttributes redirect) {
		session.removeAttribute(PENDING_ROSTER);
		String fileName = fileName(file);
		if (file.isEmpty() || !fileName.toLowerCase().endsWith(".xlsx")) {
			redirect.addFlashAttribute("error", "수강명부 xlsx 파일을 선택하세요.");
			return "redirect:/admin/roster";
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			redirect.addFlashAttribute("error", "업로드한 파일을 읽을 수 없습니다.");
			return "redirect:/admin/roster";
		}
		try {
			redirect.addFlashAttribute("result", importService.importRoster(fileName,
					new ByteArrayInputStream(content), endsOn, List.of()));
		}
		catch (RosterImportService.ConfirmationRequiredException ex) {
			session.setAttribute(PENDING_ROSTER, new PendingRoster(fileName, content, endsOn, ex.getDeletions()));
		}
		catch (RosterParseException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/admin/roster";
	}

	/**
	 * 명부에 없는 수강의 동의서 삭제를 확인하고 보류한 명부를 반영한다.
	 * 확인 화면 이후 삭제 대상 동의서가 늘었으면 반영하지 않고 새 목록으로 다시 확인받는다.
	 */
	@PostMapping("/roster/confirm")
	String confirmRoster(HttpSession session, RedirectAttributes redirect) {
		if (!(session.getAttribute(PENDING_ROSTER) instanceof PendingRoster pending)) {
			redirect.addFlashAttribute("error", "확인할 업로드가 없습니다. 명부를 다시 올려 주세요.");
			return "redirect:/admin/roster";
		}
		try {
			redirect.addFlashAttribute("result", importService.importRoster(pending.fileName(),
					new ByteArrayInputStream(pending.content()), pending.endsOn(), pending.deletions()));
			session.removeAttribute(PENDING_ROSTER);
		}
		catch (RosterImportService.ConfirmationRequiredException ex) {
			session.setAttribute(PENDING_ROSTER,
					new PendingRoster(pending.fileName(), pending.content(), pending.endsOn(), ex.getDeletions()));
			redirect.addFlashAttribute("error", "확인하는 사이 삭제될 동의서가 바뀌었습니다. 목록을 다시 확인하세요.");
		}
		catch (RosterParseException ex) {
			session.removeAttribute(PENDING_ROSTER);
			redirect.addFlashAttribute("error", ex.getMessage());
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
