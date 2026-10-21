package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/** 브라우저 서명 패드가 보내는 것과 같은 투명 배경 PNG 서명. */
public final class SignatureFixtures {

	private SignatureFixtures() {
	}

	public static byte[] png() {
		BufferedImage image = new BufferedImage(600, 240, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(20, 20, 30));
		g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawPolyline(new int[] {60, 140, 190, 260, 330, 400, 470, 540}, new int[] {170, 60, 190, 70, 180, 80, 170, 90}, 8);
		g.dispose();
		return encode(image);
	}

	public static byte[] blankPng() {
		return encode(new BufferedImage(600, 240, BufferedImage.TYPE_INT_ARGB));
	}

	public static String dataUrl(byte[] png) {
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
	}

	private static byte[] encode(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
