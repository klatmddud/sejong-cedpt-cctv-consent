package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 원본 동의서 PDF(consent_ko.pdf, consent_en.pdf)에 동의 체크, 서명 일자, 성명, 서명 이미지를 덧입힌다.
 *
 * 좌표는 원본 PDF에서 PDFBox로 뽑은 글자 위치(포인트, 왼쪽 아래 원점)다. 원본 양식이 바뀌면 다시 측정해야 한다.
 * 한글본의 "동의인 (사인)" 줄은 이름을 넣을 공간이 없어서 지우고 "동의인 성명 (사인)"으로 다시 쓴다.
 * 관리자 일괄 다운로드에서 과목을 나누는 표지(cover)도 같은 글꼴로 그린다.
 *
 * 이름과 과목명은 세종대 포털과 명부에서 오므로 어떤 글자든 올 수 있다. 나눔고딕에 없는 글자(악센트 붙은 라틴 문자, 키릴,
 * 그리스 문자)는 Noto Sans로 그리고, 둘 다 없는 글자(한자 등)는 악센트를 뗀 글자나 ?로 바꿔, 글자 하나 때문에 동의서를
 * 만들지 못하는 일이 없게 한다.
 */
@Component
public class ConsentPdfRenderer {

	private static final String FONT = "fonts/NanumGothic-Regular.ttf";
	private static final String FALLBACK_FONT = "fonts/NotoSans-Regular.ttf";
	private static final Logger log = LoggerFactory.getLogger(ConsentPdfRenderer.class);
	private static final float SIGNATURE_MAX_WIDTH = 72;
	private static final float SIGNATURE_MAX_HEIGHT = 34;
	// 서명은 72×34pt 안에 그려진다. 휴대폰 서명(1000px 넘음)을 그대로 넣으면 PDF 이미지로 바꾸는 데 렌더링 시간 대부분이
	// 들어, 인쇄에 충분한 300dpi 크기로 줄여서 넣는다
	private static final float SIGNATURE_DPI = 300;

	// PDF 렌더링은 이 서버(약 1/8 OCPU, 힙 256MB)에서 CPU·힙을 많이 쓴다. 서명을 크게 만들어 동시에 여러 번
	// 요청하면 스레드와 힙을 고갈시킬 수 있어, 서버 전체에서 동시에 도는 렌더 수를 제한한다. 빈자리를 잠깐 기다리고
	// 그래도 없으면 503으로 거절한다(OutOfMemoryError로 전체가 멈추는 것보다 낫다).
	private static final int MAX_CONCURRENT_RENDERS = 2;
	private static final Duration RENDER_WAIT = Duration.ofSeconds(3);

	private final Map<ConsentLanguage, byte[]> templates = new EnumMap<>(ConsentLanguage.class);
	private final byte[] font;
	private final byte[] fallbackFont;
	private final Semaphore renderSlots = new Semaphore(MAX_CONCURRENT_RENDERS);

	public ConsentPdfRenderer() {
		for (ConsentLanguage language : ConsentLanguage.values()) {
			templates.put(language, read(language.template()));
		}
		this.font = read(FONT);
		this.fallbackFont = read(FALLBACK_FONT);
	}

	/** 한 PDF 문서에서 쓰는 글꼴. 보조 글꼴은 필요한 글자가 나올 때만 문서에 넣는다. */
	private static final class Fonts {

		private final PDDocument document;
		private final byte[] fallbackBytes;
		private final PDFont primary;
		private PDFont fallback;

		Fonts(PDDocument document, byte[] primaryBytes, byte[] fallbackBytes) throws IOException {
			this.document = document;
			this.fallbackBytes = fallbackBytes;
			this.primary = PDType0Font.load(document, new ByteArrayInputStream(primaryBytes), true);
		}

		/** @return ch를 그릴 수 있는 글꼴. 둘 다 없으면 null */
		PDFont fontFor(String ch) throws IOException {
			if (canDraw(primary, ch)) {
				return primary;
			}
			if (fallback == null) {
				fallback = PDType0Font.load(document, new ByteArrayInputStream(fallbackBytes), true);
			}
			return canDraw(fallback, ch) ? fallback : null;
		}

		private static boolean canDraw(PDFont font, String ch) {
			try {
				font.encode(ch);
				return true;
			}
			catch (IOException | IllegalArgumentException ex) {
				return false;
			}
		}

	}

	/** 같은 글꼴로 이어 그릴 글자 묶음 */
	private record Run(PDFont font, String text) {
	}

	/**
	 * text를 글꼴별 묶음으로 나눈다. 먼저 NFKC로 호환 글자를 풀고(Ⅱ → II), 제어 문자는 공백, 보이지 않는 서식 문자(방향 바꿈
	 * 등)는 뺀다. 두 글꼴 모두에 없는 글자는 악센트를 뗀 글자로, 그래도 없으면 ?로 바꾼다.
	 */
	private static List<Run> runs(Fonts fonts, String text) throws IOException {
		String clean = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
				.replaceAll("\\p{Cntrl}", " ")
				.replaceAll("\\p{Cf}", "");
		List<Run> runs = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		PDFont currentFont = null;
		for (int i = 0; i < clean.length(); ) {
			int codePoint = clean.codePointAt(i);
			i += Character.charCount(codePoint);
			String ch = Character.toString(codePoint);
			PDFont font = fonts.fontFor(ch);
			if (font == null) {
				String base = Normalizer.normalize(ch, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
				font = base.isEmpty() ? null : fonts.fontFor(base);
				ch = font == null ? "?" : base;
				font = font == null ? fonts.primary : font;
				// 이름은 개인정보라 남기지 않고 글자 번호만 남긴다
				log.warn("동의서 PDF 글꼴에 없는 글자 U+{}를 바꿔 그림", "%04X".formatted(codePoint));
			}
			if (font != currentFont && !current.isEmpty()) {
				runs.add(new Run(currentFont, current.toString()));
				current.setLength(0);
			}
			currentFont = font;
			current.append(ch);
		}
		if (!current.isEmpty()) {
			runs.add(new Run(currentFont, current.toString()));
		}
		return runs;
	}

	public byte[] render(ConsentLanguage language, String signerName, ZonedDateTime signedAt, byte[] signaturePng) {
		acquireRenderSlot();
		try (PDDocument document = Loader.loadPDF(templates.get(language))) {
			PDPage page = document.getPage(0);
			Fonts font = new Fonts(document, this.font, fallbackFont);
			PDImageXObject signature = LosslessFactory.createFromImage(document, signatureForPdf(signaturePng));
			try (PDPageContentStream content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
				if (language == ConsentLanguage.KO) {
					drawKorean(content, font, signerName, signedAt, signature);
				}
				else {
					drawEnglish(content, font, signerName, signedAt, signature);
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("동의서 PDF를 만들 수 없습니다.", ex);
		}
		finally {
			renderSlots.release();
		}
	}

	/** 동시 렌더 슬롯을 얻는다. RENDER_WAIT 안에 못 얻으면 {@link RenderBusyException}(503)을 던진다. */
	private void acquireRenderSlot() {
		try {
			if (!renderSlots.tryAcquire(RENDER_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new RenderBusyException();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RenderBusyException();
		}
	}

	/** 동시 렌더가 한도에 차서 지금은 PDF를 만들 수 없을 때. */
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public static class RenderBusyException extends RuntimeException {
	}

	/**
	 * 동의서 묶음에서 과목을 나누는 흰 표지 한 장(A4). 가운데에 표시(시작/끝), 강의명, 학수번호, 강의실, 담당교수를 쓴다.
	 *
	 * @param mark "시작" 또는 "끝"
	 */
	public byte[] cover(String mark, String courseName, String code, String room, String professor) {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			Fonts font = new Fonts(document, this.font, fallbackFont);
			float center = PDRectangle.A4.getWidth() / 2;
			float maxWidth = PDRectangle.A4.getWidth() - 120;
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				drawCentered(content, font, 40, mark, center, 560);
				drawCentered(content, font, fitSize(font, courseName, 26, maxWidth), courseName, center, 480);
				float baseline = 430;
				for (String line : List.of("학수번호  " + code, "강의실  " + room, "담당교수  " + orDash(professor))) {
					drawCentered(content, font, fitSize(font, line, 15, maxWidth), line, center, baseline);
					baseline -= 28;
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("동의서 표지를 만들 수 없습니다.", ex);
		}
	}

	private static String orDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private static void drawKorean(PDPageContentStream content, Fonts font, String name, ZonedDateTime at,
			PDImageXObject signature) throws IOException {
		// □ 동의함: 상자 409.6~420.6, 기준선 300.6
		drawCheck(content, 415.1f, 304.6f);

		// "20 년 월 일": 20 끝 259.7, 년 270.6~281.6, 월 303.6~314.6, 일 336.4, 기준선 234.7
		float size = 11;
		drawCentered(content, font, 10, String.format("%02d", at.getYear() % 100), (259.7f + 270.6f) / 2, 234.7f);
		drawCentered(content, font, size, String.valueOf(at.getMonthValue()), (281.6f + 303.6f) / 2, 234.7f);
		drawCentered(content, font, size, String.valueOf(at.getDayOfMonth()), (314.6f + 336.4f) / 2, 234.7f);

		// "동의인 (사인)" 453.7~526.4, 기준선 177.8 → 지우고 오른쪽 끝을 맞춰 다시 쓴다
		float baseline = 177.8f;
		float right = 526.4f;
		float gap = 8;
		content.setNonStrokingColor(1f, 1f, 1f);
		content.addRect(440, baseline - 4, right - 440 + 2, 16);
		content.fill();
		content.setNonStrokingColor(0f, 0f, 0f);

		float signLabelWidth = width(font, size, "(사인)");
		float signLabelX = right - signLabelWidth;
		float nameSize = fitSize(font, name, size, 90);
		float nameX = signLabelX - gap - width(font, nameSize, name);
		float labelX = nameX - gap - width(font, size, "동의인");
		drawText(content, font, size, "동의인", labelX, baseline);
		drawText(content, font, nameSize, name, nameX, baseline);
		drawText(content, font, size, "(사인)", signLabelX, baseline);
		drawSignature(content, signature, signLabelX + signLabelWidth / 2, baseline + 4);
	}

	private static void drawEnglish(PDPageContentStream content, Fonts font, String name, ZonedDateTime at,
			PDImageXObject signature) throws IOException {
		// ☐ I agree: 상자 460.4~470.3, 기준선 317.0
		drawCheck(content, 465.3f, 320.8f);

		// Date: 20____ (Year) ____ (Month) ____ (Day), 밑줄 위에 숫자를 쓴다. 기준선 237.0
		float size = 10;
		float baseline = 238.5f;
		drawCentered(content, font, size, String.format("%02d", at.getYear() % 100), (225.8f + 248.0f) / 2, baseline);
		drawCentered(content, font, size, String.valueOf(at.getMonthValue()), (289.7f + 312.0f) / 2, baseline);
		drawCentered(content, font, size, String.valueOf(at.getDayOfMonth()), (360.3f + 382.6f) / 2, baseline);

		// Consenting Party: ______ (Signature), 밑줄 348.6~471.0, (Signature) 476.5~526.0, 기준선 175.0
		float nameSize = fitSize(font, name, size, 471.0f - 348.6f - 8);
		drawCentered(content, font, nameSize, name, (348.6f + 471.0f) / 2, 176.5f);
		drawSignature(content, signature, (476.5f + 526.0f) / 2, 179);
	}

	/** 상자 가운데에 체크 표시를 선으로 그린다 (글꼴에 없는 기호를 피하려고). */
	private static void drawCheck(PDPageContentStream content, float centerX, float centerY) throws IOException {
		content.setStrokingColor(0.05f, 0.05f, 0.05f);
		content.setLineWidth(1.6f);
		content.setLineCapStyle(1);
		content.setLineJoinStyle(1);
		content.moveTo(centerX - 4.2f, centerY + 0.2f);
		content.lineTo(centerX - 1.2f, centerY - 3.4f);
		content.lineTo(centerX + 4.6f, centerY + 4.6f);
		content.stroke();
	}

	/**
	 * 서명 PNG를 PDF에 그릴 크기의 300dpi로 줄인다. 이미 작으면 그대로 둔다. 가는 획이 끊기지 않도록 절반씩 여러 번 줄인다.
	 */
	static BufferedImage signatureForPdf(byte[] png) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		if (image == null) {
			throw new IOException("서명 이미지를 읽을 수 없습니다.");
		}
		float fit = Math.min(SIGNATURE_MAX_WIDTH / image.getWidth(), SIGNATURE_MAX_HEIGHT / image.getHeight());
		int targetWidth = Math.max(1, Math.round(image.getWidth() * fit * SIGNATURE_DPI / 72));
		int targetHeight = Math.max(1, Math.round(image.getHeight() * fit * SIGNATURE_DPI / 72));
		while (image.getWidth() > targetWidth) {
			int width = Math.max(targetWidth, image.getWidth() / 2);
			int height = Math.max(targetHeight, image.getHeight() / 2);
			BufferedImage smaller = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = smaller.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(image, 0, 0, width, height, null);
			graphics.dispose();
			image = smaller;
		}
		return image;
	}

	/** 서명 이미지를 가로세로 비율을 유지해 (centerX, centerY) 중심의 상자에 맞춘다. */
	private static void drawSignature(PDPageContentStream content, PDImageXObject signature, float centerX,
			float centerY) throws IOException {
		float scale = Math.min(SIGNATURE_MAX_WIDTH / signature.getWidth(), SIGNATURE_MAX_HEIGHT / signature.getHeight());
		float width = signature.getWidth() * scale;
		float height = signature.getHeight() * scale;
		content.drawImage(signature, centerX - width / 2, centerY - height / 2, width, height);
	}

	private static void drawCentered(PDPageContentStream content, Fonts font, float size, String text, float centerX,
			float baseline) throws IOException {
		drawText(content, font, size, text, centerX - width(font, size, text) / 2, baseline);
	}

	private static void drawText(PDPageContentStream content, Fonts font, float size, String text, float x,
			float baseline) throws IOException {
		content.beginText();
		content.newLineAtOffset(x, baseline);
		for (Run run : runs(font, text)) {
			content.setFont(run.font(), size);
			content.showText(run.text());
		}
		content.endText();
	}

	private static float width(Fonts font, float size, String text) throws IOException {
		float width = 0;
		for (Run run : runs(font, text)) {
			width += run.font().getStringWidth(run.text()) / 1000 * size;
		}
		return width;
	}

	/** 긴 이름은 maxWidth에 들어가도록 글자 크기를 줄인다 (최소 6pt). */
	private static float fitSize(Fonts font, String text, float size, float maxWidth) throws IOException {
		float width = width(font, size, text);
		return width <= maxWidth ? size : Math.max(6, size * maxWidth / width);
	}

	private static byte[] read(String path) {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(path + "를 읽을 수 없습니다.", ex);
		}
	}

}
