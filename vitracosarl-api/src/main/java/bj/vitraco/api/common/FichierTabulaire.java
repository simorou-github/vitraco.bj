package bj.vitraco.api.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lecture d'un fichier d'import (CSV, texte ou Excel .xlsx) en lignes de cellules : la première ligne porte les titres de
 * colonnes. Partagé par les imports de colisage et de stock initial.
 */
public final class FichierTabulaire {

	private FichierTabulaire() {
	}

	public static List<String[]> lire(String nom, byte[] contenu) {
		String n = nom.toLowerCase(Locale.ROOT);
		if (n.endsWith(".xlsx")) {
			return lireExcel(contenu);
		}
		if (n.endsWith(".csv") || n.endsWith(".txt")) {
			return lireCsv(decoder(contenu));
		}
		throw new ErreurValidationException("fichier", "Format non pris en charge : envoyez un fichier CSV ou Excel (.xlsx).");
	}

	private static String decoder(byte[] contenu) {
		try {
			String texte = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(contenu)).toString();
			return texte.startsWith("﻿") ? texte.substring(1) : texte;
		} catch (CharacterCodingException e) {
			// Exports Excel français : Windows-1252.
			return new String(contenu, Charset.forName("windows-1252"));
		}
	}

	public static List<String[]> lireCsv(String texte) {
		String premiere = texte.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
		char separateur = compter(premiere, ';') >= compter(premiere, ',') && compter(premiere, ';') >= compter(premiere, '\t') ? ';'
				: compter(premiere, ',') >= compter(premiere, '\t') ? ',' : '\t';
		List<String[]> lignes = new ArrayList<>();
		List<String> courante = new ArrayList<>();
		StringBuilder champ = new StringBuilder();
		boolean guillemets = false;
		for (int i = 0; i < texte.length(); i++) {
			char c = texte.charAt(i);
			if (guillemets) {
				if (c == '"' && i + 1 < texte.length() && texte.charAt(i + 1) == '"') {
					champ.append('"');
					i++;
				} else if (c == '"') {
					guillemets = false;
				} else {
					champ.append(c);
				}
			} else if (c == '"') {
				guillemets = true;
			} else if (c == separateur) {
				courante.add(champ.toString());
				champ.setLength(0);
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < texte.length() && texte.charAt(i + 1) == '\n') {
					i++;
				}
				courante.add(champ.toString());
				champ.setLength(0);
				lignes.add(courante.toArray(new String[0]));
				courante = new ArrayList<>();
			} else {
				champ.append(c);
			}
		}
		if (champ.length() > 0 || !courante.isEmpty()) {
			courante.add(champ.toString());
			lignes.add(courante.toArray(new String[0]));
		}
		return lignes;
	}

	private static long compter(String texte, char c) {
		return texte.chars().filter(x -> x == c).count();
	}

	private static List<String[]> lireExcel(byte[] contenu) {
		try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(contenu))) {
			Sheet feuille = classeur.getSheetAt(0);
			DataFormatter formateur = new DataFormatter();
			List<String[]> lignes = new ArrayList<>();
			int derniere = feuille.getLastRowNum();
			for (int r = 0; r <= derniere; r++) {
				Row ligne = feuille.getRow(r);
				if (ligne == null) {
					lignes.add(new String[0]); // une ligne vide garde la numérotation du tableur
					continue;
				}
				short fin = ligne.getLastCellNum();
				String[] cellules = new String[Math.max(fin, 0)];
				for (int c = 0; c < cellules.length; c++) {
					Cell cell = ligne.getCell(c);
					cellules[c] = cell == null ? "" : formateur.formatCellValue(cell).trim();
				}
				lignes.add(cellules);
			}
			return lignes;
		} catch (IOException | RuntimeException e) {
			throw new ErreurValidationException("fichier", "Ce fichier Excel est illisible : enregistrez-le au format .xlsx.");
		}
	}

	/** Titre de colonne ramené à des lettres et chiffres minuscules, sans accent : « N° Châssis » devient « nchassis ». */
	public static String normaliser(String texte) {
		return Normalizer.normalize(texte == null ? "" : texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	public static String cellule(String[] cellules, Integer index) {
		return index == null || index >= cellules.length || cellules[index] == null ? "" : cellules[index].trim();
	}

}
