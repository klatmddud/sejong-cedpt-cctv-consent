package kr.ac.sejong.cedpt.cctvconsent.admin;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
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
	String rosterForm(Model model) {
		model.addAttribute("uploads", queries.recentUploads());
		return "admin/roster";
	}

	@PostMapping("/roster")
	String uploadRoster(@RequestParam("file") MultipartFile file,
			@RequestParam("endsOn") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
			RedirectAttributes redirect) {
		String fileName = fileName(file);
		if (file.isEmpty() || !fileName.toLowerCase().endsWith(".xlsx")) {
			redirect.addFlashAttribute("error", "수강명부 xlsx 파일을 선택하세요.");
			return "redirect:/admin/roster";
		}
		try (InputStream in = file.getInputStream()) {
			redirect.addFlashAttribute("result", importService.importRoster(fileName, in, endsOn));
		}
		catch (RosterParseException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		catch (IOException ex) {
			redirect.addFlashAttribute("error", "업로드한 파일을 읽을 수 없습니다.");
		}
		return "redirect:/admin/roster";
	}

	/** 브라우저가 보낸 경로를 떼고 DB 칼럼 길이(255)에 맞춘다. */
	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

}
