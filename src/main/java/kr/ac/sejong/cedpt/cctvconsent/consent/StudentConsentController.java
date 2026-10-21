package kr.ac.sejong.cedpt.cctvconsent.consent;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentPrincipal;
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

@Controller
class StudentConsentController {

	private final ConsentService consents;

	StudentConsentController(ConsentService consents) {
		this.consents = consents;
	}

	@GetMapping("/login")
	String login(HttpSession session, Model model) {
		// 로그인 실패 이유(학번 잠김, 포털 오류 등)를 한 번만 보여 준다
		Object error = session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		if (error instanceof AuthenticationException ex) {
			model.addAttribute("loginError", ex.getMessage());
			session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		}
		return "student/login";
	}

	@GetMapping("/consents")
	String list(@AuthenticationPrincipal StudentPrincipal student, Model model) {
		model.addAttribute("student", student);
		model.addAttribute("courses", consents.courses(student.studentId()));
		return "student/consents";
	}

	@GetMapping("/consents/{enrollmentId}")
	String form(@AuthenticationPrincipal StudentPrincipal student, @PathVariable long enrollmentId,
			@RequestParam(defaultValue = "ko") String lang, Model model) {
		ConsentService.CourseConsent course = consents.course(student.studentId(), enrollmentId);
		if (course.signed()) {
			return "redirect:/consents";
		}
		model.addAttribute("student", student);
		model.addAttribute("course", course);
		model.addAttribute("lang", ConsentLanguage.fromCode(lang).code());
		return "student/consent-form";
	}

	@PostMapping("/consents/{enrollmentId}")
	String submit(@AuthenticationPrincipal StudentPrincipal student, @PathVariable long enrollmentId,
			@RequestParam(defaultValue = "ko") String lang, @RequestParam(defaultValue = "false") boolean agreed,
			@RequestParam(required = false) String signature, RedirectAttributes redirect) {
		try {
			consents.submit(student.studentId(), student.name(), enrollmentId, ConsentLanguage.fromCode(lang), agreed,
					signature);
		}
		catch (ConsentException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/consents/" + enrollmentId + "?lang=" + ConsentLanguage.fromCode(lang).code();
		}
		ConsentService.CourseConsent course = consents.course(student.studentId(), enrollmentId);
		redirect.addFlashAttribute("submitted", course.courseName());
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
