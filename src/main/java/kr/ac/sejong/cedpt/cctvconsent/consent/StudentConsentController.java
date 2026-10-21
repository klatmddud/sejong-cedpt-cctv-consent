package kr.ac.sejong.cedpt.cctvconsent.consent;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentPrincipal;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 학생 화면. 언어는 첫 화면에서 고른 값(쿠키)을 따른다.
 */
@Controller
class StudentConsentController {

	private final ConsentService consents;
	private final MessageSource messages;

	StudentConsentController(ConsentService consents, MessageSource messages) {
		this.consents = consents;
		this.messages = messages;
	}

	@GetMapping("/")
	String landing() {
		return "student/landing";
	}

	@GetMapping("/login")
	String login(HttpSession session, Model model, Locale locale) {
		// 로그인 실패 이유(학번 잠김, 포털 오류 등)를 한 번만 보여 준다
		Object error = session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		if (error instanceof AuthenticationException ex) {
			model.addAttribute("loginError", messages.getMessage(ex.getMessage(), null, ex.getMessage(), locale));
			session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		}
		return "student/login";
	}

	@GetMapping("/consents")
	String list(@AuthenticationPrincipal StudentPrincipal student, Model model) {
		List<ConsentService.CourseConsent> courses = consents.courses(student.studentId());
		model.addAttribute("student", student);
		model.addAttribute("courses", courses);
		model.addAttribute("pending", courses.stream().filter(course -> !course.signed()).count());
		return "student/consents";
	}

	@GetMapping("/consents/sign")
	String form(@AuthenticationPrincipal StudentPrincipal student, Model model) {
		List<ConsentService.CourseConsent> pending = consents.courses(student.studentId()).stream()
				.filter(course -> !course.signed())
				.toList();
		if (pending.isEmpty()) {
			return "redirect:/consents";
		}
		model.addAttribute("student", student);
		model.addAttribute("courses", pending);
		return "student/consent-form";
	}

	@PostMapping("/consents/sign")
	String submit(@AuthenticationPrincipal StudentPrincipal student,
			@RequestParam(name = "enrollmentIds", required = false) List<Long> enrollmentIds,
			@RequestParam(defaultValue = "false") boolean agreed, @RequestParam(required = false) String signature,
			Locale locale, RedirectAttributes redirect) {
		int submitted;
		try {
			submitted = consents.submit(student.studentId(), student.name(), enrollmentIds,
					ConsentLanguage.fromLocale(locale), agreed, signature);
		}
		catch (ConsentException ex) {
			redirect.addFlashAttribute("error", messages.getMessage(ex.getMessage(), null, ex.getMessage(), locale));
			return "redirect:/consents/sign";
		}
		redirect.addFlashAttribute("submitted", submitted);
		return "redirect:/consents";
	}

	@GetMapping("/consents/{enrollmentId}/pdf")
	ResponseEntity<byte[]> pdf(@AuthenticationPrincipal StudentPrincipal student, @PathVariable long enrollmentId) {
		ConsentService.PdfFile file = consents.pdf(student.studentId(), enrollmentId);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
				.body(file.content());
	}

}
