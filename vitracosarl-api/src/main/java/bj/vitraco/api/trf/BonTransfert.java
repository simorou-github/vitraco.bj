package bj.vitraco.api.trf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Bon de transfert (EF-TRF-06) : il accompagne les motos, avec la liste des châssis et les cases de signature. */
final class BonTransfert {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE).withZone(ZoneId.of("Africa/Porto-Novo"));
	private static final Font TITRE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
	private static final Font SOUS_TITRE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
	private static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 9);
	private static final Font GRAS = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

	private BonTransfert() {
	}

	static byte[] generer(TransfertDto t) {
		ByteArrayOutputStream sortie = new ByteArrayOutputStream();
		Document document = new Document(PageSize.A4, 36, 36, 36, 36);
		try {
			PdfWriter.getInstance(document, sortie);
			document.open();
			document.add(new Paragraph("VITRACO Moto", NORMAL));
			document.add(new Paragraph("Bon de transfert " + t.numero(), TITRE));
			document.add(new Paragraph(" "));

			PdfPTable infos = new PdfPTable(new float[]{1.2f, 2.8f});
			infos.setWidthPercentage(100);
			ligne(infos, "De", t.source().nom() + " (" + t.source().code() + ")");
			ligne(infos, "Vers", t.destination().nom() + " (" + t.destination().code() + ")");
			ligne(infos, "Expédié par", t.expediePar() + ", le " + DATE.format(t.expedieLe()));
			ligne(infos, "Statut", t.statutLibelle() + " — " + t.nbRecus() + " / " + t.nbChassis() + " châssis réceptionné(s)");
			if (t.retourDe() != null) {
				ligne(infos, "Corrige", "Transfert " + t.retourDe());
			}
			document.add(infos);

			document.add(new Paragraph(" "));
			document.add(new Paragraph("Châssis transférés (" + t.nbChassis() + ")", SOUS_TITRE));
			PdfPTable lignes = new PdfPTable(new float[]{0.5f, 2.4f, 2.6f, 1.6f, 2f});
			lignes.setWidthPercentage(100);
			for (String h : new String[]{"N°", "Châssis", "Modèle", "Code article", "Réception"}) {
				entete(lignes, h);
			}
			int rang = 1;
			for (TransfertDto.Ligne l : t.lignes()) {
				cellule(lignes, String.valueOf(rang++), Element.ALIGN_RIGHT);
				cellule(lignes, l.numero(), Element.ALIGN_LEFT);
				cellule(lignes, l.modele(), Element.ALIGN_LEFT);
				cellule(lignes, l.codeArticle(), Element.ALIGN_LEFT);
				cellule(lignes, l.recu() ? "Reçu le " + DATE.format(l.recuLe()) : "En transit", Element.ALIGN_LEFT);
			}
			document.add(lignes);

			if (t.observation() != null) {
				document.add(new Paragraph(" "));
				document.add(new Paragraph("Observations", SOUS_TITRE));
				document.add(new Paragraph(t.observation(), NORMAL));
			}

			document.add(new Paragraph(" "));
			document.add(new Paragraph(" "));
			PdfPTable signatures = new PdfPTable(3);
			signatures.setWidthPercentage(100);
			signature(signatures, "Expéditeur : " + t.expediePar());
			signature(signatures, "Transporteur");
			signature(signatures, "Réceptionnaire");
			document.add(signatures);
		} catch (DocumentException e) {
			throw new IllegalStateException("Génération du bon de transfert impossible", e);
		} finally {
			document.close();
		}
		return sortie.toByteArray();
	}

	private static void ligne(PdfPTable t, String libelle, String valeur) {
		PdfPCell a = new PdfPCell(new Phrase(libelle, GRAS));
		a.setBorder(Rectangle.NO_BORDER);
		PdfPCell b = new PdfPCell(new Phrase(valeur, NORMAL));
		b.setBorder(Rectangle.NO_BORDER);
		t.addCell(a);
		t.addCell(b);
	}

	private static void entete(PdfPTable t, String texte) {
		PdfPCell c = new PdfPCell(new Phrase(texte, GRAS));
		c.setBackgroundColor(new java.awt.Color(235, 238, 242));
		c.setPadding(4);
		t.addCell(c);
	}

	private static void cellule(PdfPTable t, String texte, int alignement) {
		PdfPCell c = new PdfPCell(new Phrase(texte == null ? "" : texte, NORMAL));
		c.setHorizontalAlignment(alignement);
		c.setPadding(3);
		t.addCell(c);
	}

	private static void signature(PdfPTable t, String texte) {
		PdfPCell c = new PdfPCell(new Phrase(texte + "\n\n\n\nSignature :", NORMAL));
		c.setMinimumHeight(70);
		c.setPadding(6);
		t.addCell(c);
	}

}
