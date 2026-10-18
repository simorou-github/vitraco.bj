package bj.vitraco.api.rec;

import bj.vitraco.api.common.ErreurMetierException;
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

/** Procès-verbal de réception (EF-REC-08) : châssis reçus, écarts, observations et cases de signature. */
final class PvReception {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE).withZone(ZoneId.of("Africa/Porto-Novo"));
	private static final Font TITRE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
	private static final Font SOUS_TITRE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
	private static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 9);
	private static final Font GRAS = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

	private PvReception() {
	}

	static byte[] generer(ReceptionDto r) {
		if (!"VALIDEE".equals(r.statut())) {
			throw new ErreurMetierException("RECEPTION_NON_VALIDEE", "Le procès-verbal n'existe qu'une fois la réception validée.");
		}
		ByteArrayOutputStream sortie = new ByteArrayOutputStream();
		Document document = new Document(PageSize.A4, 36, 36, 36, 36);
		try {
			PdfWriter.getInstance(document, sortie);
			document.open();
			document.add(new Paragraph("VITRACO Moto", FontFactory.getFont(FontFactory.HELVETICA, 9)));
			document.add(new Paragraph("Procès-verbal de réception " + r.numero(), TITRE));
			document.add(new Paragraph(" "));

			PdfPTable infos = new PdfPTable(new float[]{1.2f, 2.8f});
			infos.setWidthPercentage(100);
			ligne(infos, "Magasin", r.emplacement().nom() + " (" + r.emplacement().code() + ")");
			ligne(infos, "Arrivage", "B/L " + r.arrivage().numeroBl());
			ligne(infos, "Commande", r.arrivage().commandeNumero() + " — " + r.arrivage().fournisseur());
			ligne(infos, "Ouverte par", r.creePar() + ", le " + DATE.format(r.creeLe()));
			ligne(infos, "Validée par", r.validePar() + ", le " + DATE.format(r.valideLe()));
			document.add(infos);

			document.add(new Paragraph(" "));
			document.add(new Paragraph("Rapprochement attendu / reçu", SOUS_TITRE));
			PdfPTable resume = new PdfPTable(new float[]{3f, 1f, 1f, 1f});
			resume.setWidthPercentage(100);
			for (String h : new String[]{"Modèle", "Attendu", "Reçu", "Écart"}) {
				entete(resume, h);
			}
			for (ReceptionDto.ResumeModele m : r.resume()) {
				cellule(resume, m.codeArticle() + " — " + m.libelle(), Element.ALIGN_LEFT);
				cellule(resume, String.valueOf(m.attendu()), Element.ALIGN_RIGHT);
				cellule(resume, String.valueOf(m.recu()), Element.ALIGN_RIGHT);
				cellule(resume, (m.ecart() > 0 ? "+" : "") + m.ecart(), Element.ALIGN_RIGHT);
			}
			cellule(resume, "Total", Element.ALIGN_LEFT);
			cellule(resume, String.valueOf(r.nbAttendus()), Element.ALIGN_RIGHT);
			cellule(resume, String.valueOf(r.nbRecus()), Element.ALIGN_RIGHT);
			cellule(resume, String.valueOf(r.nbRecus() - r.nbAttendus()), Element.ALIGN_RIGHT);
			document.add(resume);

			document.add(new Paragraph(" "));
			document.add(new Paragraph("Châssis reçus (" + r.nbRecus() + ")", SOUS_TITRE));
			PdfPTable lignes = new PdfPTable(new float[]{2.2f, 2f, 1.3f, 3f});
			lignes.setWidthPercentage(100);
			for (String h : new String[]{"Châssis", "Modèle", "État", "Observation"}) {
				entete(lignes, h);
			}
			for (ReceptionDto.Ligne l : r.lignes().reversed()) {
				cellule(lignes, l.numero(), Element.ALIGN_LEFT);
				cellule(lignes, l.codeArticle(), Element.ALIGN_LEFT);
				cellule(lignes, l.endommage() ? "Bloqué" : "Disponible", Element.ALIGN_LEFT);
				String observation = switch (l.nature()) {
					case "INATTENDU" -> "Non annoncé — " + (l.motif() == null ? "" : l.motif());
					default -> l.motif() == null ? "" : l.motif();
				};
				cellule(lignes, observation, Element.ALIGN_LEFT);
			}
			document.add(lignes);

			if (!r.manquants().isEmpty()) {
				document.add(new Paragraph(" "));
				document.add(new Paragraph("Châssis annoncés et non reçus (" + r.manquants().size() + ")", SOUS_TITRE));
				document.add(new Paragraph(String.join(", ", r.manquants()), NORMAL));
			}
			if (r.observation() != null) {
				document.add(new Paragraph(" "));
				document.add(new Paragraph("Observations", SOUS_TITRE));
				document.add(new Paragraph(r.observation(), NORMAL));
			}

			document.add(new Paragraph(" "));
			document.add(new Paragraph(" "));
			PdfPTable signatures = new PdfPTable(2);
			signatures.setWidthPercentage(100);
			signature(signatures, "Réceptionnaire : " + r.creePar());
			signature(signatures, "Responsable magasin : " + r.validePar());
			document.add(signatures);
		} catch (DocumentException e) {
			throw new IllegalStateException("Génération du PV impossible", e);
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
		c.setBackgroundColor(new java.awt.Color(230, 235, 240));
		c.setPadding(4);
		t.addCell(c);
	}

	private static void cellule(PdfPTable t, String texte, int alignement) {
		PdfPCell c = new PdfPCell(new Phrase(texte, NORMAL));
		c.setHorizontalAlignment(alignement);
		c.setPadding(3);
		t.addCell(c);
	}

	private static void signature(PdfPTable t, String texte) {
		PdfPCell c = new PdfPCell(new Phrase(texte + "\n\n\n\nSignature :", NORMAL));
		c.setPadding(8);
		c.setMinimumHeight(70);
		t.addCell(c);
	}

}
