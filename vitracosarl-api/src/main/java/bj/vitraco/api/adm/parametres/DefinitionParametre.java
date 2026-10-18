package bj.vitraco.api.adm.parametres;

import java.math.BigDecimal;

/**
 * Un paramètre connu de l'application. Le catalogue vit dans le code : seules les valeurs sont
 * saisies, pour qu'une clé inconnue ne puisse jamais entrer en base ni être lue par un module. {@code defaut} n'existe que
 * pour une politique (par exemple la séparation des tâches) dont la valeur sûre s'applique tant que personne ne l'a réglée ;
 * les seuils et les taux n'en ont jamais.
 */
public record DefinitionParametre(String cle, GroupeParametre groupe, String libelle, String aide, TypeParametre type,
								  String unite, BigDecimal min, BigDecimal max, String defaut) {
}
