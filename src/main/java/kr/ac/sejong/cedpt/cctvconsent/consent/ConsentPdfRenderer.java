package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 원본 동의서 PDF(consent_ko.pdf, consent_en.pdf)에 동의 체크, 서명 일자, 성명, 서명 이미지를 덧입힌다.
 *
 * 좌표는 원본 PDF에서 PDFBox로 뽑은 글자 위치(포인트, 왼쪽 아래 원점)다. 원본 양식이 바뀌면 다시 측정해야 한다.
 * 한글본의 "동의인 (사인)" 줄은 이름을 넣을 공간이 없어서 지우고 "동의인 성명 (사인)"으로 다시 쓴다.
 * 관리자 일괄 다운로드에서 과목을 나누는 표지(cover)도 같은 글꼴로 그린다.
 */
@Component
public class ConsentPdfRenderer {

	private static final String FONT = "fonts/NanumGothic-Regular.ttf";
	private static final float SIGNATURE_MAX_WIDTH = 72;
	private static final float SIGNATURE_MAX_HEIGHT = 34;

	private final Map<ConsentLanguage, byte[]> templates = new EnumMap<>(ConsentLanguage.class);
	private final byte[] font;

	public ConsentPdfRenderer() {
		for (ConsentLanguage language : ConsentLanguage.values()) {
			templates.put(language, read(language.template()));
		}
		this.font = read(FONT);
	}

	public byte[] render(ConsentLanguage language, String signerName, ZonedDateTime signedAt, byte[] signaturePng) {
		try (PDDocument document = Loader.loadPDF(templates.get(language))) {
			PDPage page = document.getPage(0);
			PDFont font = PDType0Font.load(document, new ByteArrayInputStream(this.font), true);
			PDImageXObject signature = PDImageXObject.createFromByteArray(document, signaturePng, "signature");
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
			PDFont font = PDType0Font.load(document, new ByteArrayInputStream(this.font), true);
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

	private static void drawKorean(PDPageContentStream content, PDFont font, String name, ZonedDateTime at,
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

	private static void drawEnglish(PDPageContentStream content, PDFont font, String name, ZonedDateTime at,
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

	/** 서명 이미지를 가로세로 비율을 유지해 (centerX, centerY) 중심의 상자에 맞춘다. */
	private static void drawSignature(PDPageContentStream content, PDImageXObject signature, float centerX,
			float centerY) throws IOException {
		float scale = Math.min(SIGNATURE_MAX_WIDTH / signature.getWidth(), SIGNATURE_MAX_HEIGHT / signature.getHeight());
		float width = signature.getWidth() * scale;
		float height = signature.getHeight() * scale;
		content.drawImage(signature, centerX - width / 2, centerY - height / 2, width, height);
	}

	private static void drawCentered(PDPageContentStream content, PDFont font, float size, String text, float centerX,
			float baseline) throws IOException {
		drawText(content, font, size, text, centerX - width(font, size, text) / 2, baseline);
	}

	private static void drawText(PDPageContentStream content, PDFont font, float size, String text, float x,
			float baseline) throws IOException {
		content.beginText();
		content.setFont(font, size);
		content.newLineAtOffset(x, baseline);
		content.showText(text);
		content.endText();
	}

	private static float width(PDFont font, float size, String text) throws IOException {
		return font.getStringWidth(text) / 1000 * size;
	}

	/** 긴 이름은 maxWidth에 들어가도록 글자 크기를 줄인다 (최소 6pt). */
	private static float fitSize(PDFont font, String text, float size, float maxWidth) throws IOException {
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
