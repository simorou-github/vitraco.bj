# VITRACO Moto — Conception actualisée et plan de mise à jour

Référence : *IRIS SOLUTIONS — SRS, planning et offre financière*, v1.0 du 10/10/2026.
Ce document remplace, côté conception, le SRS V2 (modules 3.1 à 3.11) sur lequel le code actuel est bâti.
Code existant analysé : `vitraco-backend` (Laravel 13, MySQL, 192 tests verts) et `vitraco-frontend` (Angular 22, CoreUI).

---

## 1. Ce qui change

Le SRS 1.0 ne prolonge pas le SRS V2, il le remplace. Le produit devient un **système de contrôle interne centré sur le châssis** pour l'activité motos Honda.

| Axe | SRS V2 (code actuel) | SRS 1.0 (cible) |
|---|---|---|
| Périmètre | Motos multi-marques (SANYA, HONDA, LONCIN) **et** pièces détachées | **Motos Honda uniquement.** Pièces détachées, SAV, e-commerce : hors périmètre |
| Objet pivot | L'article, stock = compteur par site | Le **châssis** (VIN). Stock = calculé depuis la table `chassis`, jamais modifiable |
| Lieux | `sites` (magasin / point de vente, pôle) | `emplacements` : MAGASIN, BOUTIQUE (mode A ou B), DEPOT_REVENDEUR, TRANSIT |
| Achats | Commande par article, validation par seuil, retours fournisseurs | Dossier d'importation : commande → arrivages (B/L, conteneur) → frais → réception par scan de châssis. 8 statuts |
| Coût | Prix moyen pondéré par article | **Coût rendu par châssis** (achat × taux + quote-part des frais), provisoire puis définitif |
| Tiers | `tiers` unifié client/fournisseur | `clients` (REVENDEUR / NOMINATIF / DIVERS) et `fournisseurs` séparés |
| Vente | Modules vides | Commande → affectation de châssis → facture e-MECeF → livraison → règlements |
| Contrôle | Journal d'audit simple | Journal d'audit en ajout seul, anomalies A01–A15, séparation des tâches, non-suppression |
| Nouveaux domaines | — | Facturation DGI, créances, chèques, AIB, dépôt-vente, inventaires par châssis, ajustements, caisse et dépenses, documents, recherche globale, notifications, 6 tableaux de bord, rapports |
| Rôles | 10 rôles par marque et pôle | 8 rôles × 20 modules, plus périmètre d'emplacement |
| Technique | Laravel, MySQL, Angular | Laravel, **PostgreSQL**, Redis, workers, stockage S3, SSE, PWA hors ligne pour l'inventaire |

**Conséquence : le stock par article et par site actuel est faux par conception au regard du nouveau SRS.** Tout ce qui touche au stock, aux achats et aux sites doit être refondu, pas ajusté.

---

## 2. Décisions d'architecture à valider avant de coder

| # | Décision | Recommandation | Pourquoi / impact |
|---|---|---|---|
| D1 | Base de données | **Passer à PostgreSQL** (dev, tests, prod) | Le SRS §4.5 impose index unique partiel (`affectation_chassis` ACTIVE), refus d'UPDATE/DELETE sur les tables en ajout seul, recherche par trigrammes, vues matérialisées. MySQL sait l'émuler mais mal. Les tests SQLite en mémoire ne couvrent pas les triggers : tests sur PostgreSQL |
| D2 | Front | **Garder Angular 22 + CoreUI** | Le SRS propose React (§10.2) mais laisse la porte ouverte. Réécrire coûterait plus que tout le reste. Angular fait PWA, service worker et IndexedDB. À acter avec le client, l'offre mentionne React |
| D3 | Données existantes | **Repartir de zéro côté données**, réutiliser le code | Il n'y a que des données de démo. Les ~60 migrations deviennent une migration de base propre. Pas de reprise des anciennes tables |
| D4 | Pièces détachées, multi-marques | **Retirer** du code et des menus (historique conservé dans git) | Hors périmètre v1. Les garder alourdit le modèle (`articles.type`, catégories, compatibilité marques, départements) |
| D5 | Une seule marque | Garder un champ `marque` sur le modèle, **supprimer la table `brands`** et ses pivots | Le SRS garde « marque » comme attribut du catalogue, sans gestion de marques |
| D6 | Versionnage API | Préfixe `/api/v1`, OpenAPI, clé d'idempotence sur vente et règlement | SRS §10.5 et §10.7 |

**Risque immédiat à traiter d'abord : le backend n'est pas sous git** (`vitraco-backend/` est non suivi) et le frontend a 30 fichiers non commités. Avant toute refonte : initialiser git sur le backend, commiter l'état actuel et poser un tag `legacy-srs-v2`. Je ne le fais pas sans votre accord.

---

## 3. Conception cible

### 3.1 Cartographie des modules

Les 24 modules du SRS, avec leur place dans le code Laravel (`app/Domain/<Module>`) et Angular (`src/app/metier/<module>`).

| Domaine | Modules (code SRS) |
|---|---|
| Socle | ADM, AUD, NOT, DOC, RCH |
| Approvisionnement | CAT (catalogue et châssis), APP, REC, CRV |
| Stock | STK, TRF, INV, AJU |
| Vente | CLI, CMD, LIV, FAC, CRE, REG |
| Spécifique | DPV (dépôt-vente), CPT (comptabilité, caisse, dépenses) |
| Pilotage | ANO, TDB, RAP |

Recommandation de structure : un dossier par module dans `app/Domain/` (Models, Services, Policies, Events, Http), au lieu des dossiers plats actuels `app/Models`, `app/Services`. Les règles de gestion restent testables module par module (ENF-16).

### 3.2 Modèle de données cible (Laravel, noms français comme aujourd'hui)

**Référentiels et sécurité**
- `utilisateurs` (garder `users`) + `emplacement_utilisateur` (pivot) ; rôles et permissions via Spatie, déjà en place.
- `emplacements` : code, nom, `type` (MAGASIN, BOUTIQUE, DEPOT_REVENDEUR, TRANSIT), `mode_boutique` (A/B), `magasin_rattachement_id`, `revendeur_id`, actif. On garde adresse, GPS, horaires, contact déjà saisis dans `sites`.
- `modeles` : marque, modèle, version, `code_article` unique, prix achat de référence, prix de vente, **prix minimum** (≤ prix de vente), groupe de taxation, **format de châssis** (regex), actif. `prix_historique` (modele, prix, date d'effet, auteur).
- `fournisseurs` : raison sociale, pays, devise, contacts. `clients` : type, code auto, raison sociale, IFU, téléphone, zone, délai de paiement, plafond, commercial responsable, autorisation dépôt-vente, statut ; un client DIVERS générique par boutique.
- `parametres` (clé, valeur, date d'effet) : AIB, délais, seuils. `sequences` : numérotation sans trou par type, site et année.

**Approvisionnement et stock**
- `commandes_fournisseur` (+ lignes par modèle) ; `arrivages` (B/L, conteneur, dates, port, statut douane) ; `frais_arrivage` (type, montant, devise, taux, pièce) ; `receptions` (+ `reception_chassis`).
- **`chassis`** : numéro normalisé (majuscules, sans espace ni tiret) **unique**, modèle, statut (12 états du §3.4), `emplacement_id` **obligatoire**, coût de revient, arrivage, réception, date d'entrée, `version` (verrou optimiste).
- **`mouvements_stock`** : par châssis (type, source, destination, statut avant/après, document d'origine, utilisateur, horodatage). **Ajout seul.**
- `transferts` (+ lignes : expédié, reçu, écart).

**Vente et encaissement**
- `commandes_client` (+ lignes) ; `affectations_chassis` avec **index unique partiel** sur `chassis_id` quand statut = ACTIVE ; `livraisons` (+ lignes châssis).
- `factures` (+ lignes, échéances), axe fiscal et axe paiement séparés ; `certifications_fiscales` (ajout seul) ; `reglements` (`facture_id` obligatoire) ; `cheques` ; `bordereaux_remise`.

**Dépôt-vente, contrôle, comptabilité**
- `attributions_depot`, `declarations_vente_depot`, `retours_depot` ; `inventaires` + lignes ; `ajustements` ; `anomalies` ; `comptes_tresorerie`, `mouvements_tresorerie` (ajout seul), `sessions_caisse`, `depenses` ; `documents` (clé de stockage, SHA-256, versions) ; `journal_audit` (ajout seul) ; `notifications`.

**Contraintes imposées en base** (SRS §4.5) : unique `chassis.numero` ; unique partiel d'affectation ACTIVE ; `chassis.emplacement_id` non nul et modifié uniquement dans la transaction qui écrit le mouvement ; somme des règlements validés ≤ TTC ; facture NORMALISÉE immuable ; droits INSERT seul sur les 4 tables en ajout seul (triggers `BEFORE UPDATE/DELETE` qui lèvent une erreur, plus retrait du droit au compte applicatif).

### 3.3 Cœur : le châssis et ses transitions

- Enum PHP `ChassisStatut` à 12 états : EN_TRANSIT_FOURNISSEUR, A_RECEPTIONNER, DISPONIBLE, RESERVEE, EN_PREPARATION, LIVREE, EN_TRANSIT_INTERNE, DEPOT_VENTE, VENDUE_DEPOT_VENTE, RETOURNEE, BLOQUEE, SORTIE_PAR_AJUSTEMENT (+ création).
- **Table de transitions déclarative** (le tableau du §3.4 transposé en code : état de départ, événement, état d'arrivée, condition, module). Aucun autre code n'a le droit de changer `statut` ou `emplacement_id`.
- **Point d'entrée unique** `ChassisService::appliquer(Chassis, Evenement, Contexte)` : une transaction, verrou de ligne, contrôle de version, écriture du `mouvement_stock`, écriture du journal d'audit, événement métier dans une table d'envoi (outbox).
- Les « stocks » (physique, réservé, disponible, bloqué, transit, dépôt-vente, global) sont des **requêtes et vues** sur `chassis` (définitions du §5.6). Pas d'écran de saisie de quantité (EF-STK-04).

### 3.4 Mécanismes transverses à poser dans le socle

| Mécanisme | Réalisation |
|---|---|
| Non-suppression (RG-18, AUD-03) | Pas de `SoftDeletes` métier ; pas de route DELETE sur les opérations sensibles (405 + test) ; actions ANNULER, INVERSER, ARCHIVER, CORRIGER AVEC AUTORISATION |
| Audit en ajout seul | Le contrat `AuditLogger` existant est conservé ; le stockage passe à `journal_audit` (ancienne/nouvelle valeur, motif, IP, chaînage de hachage). Journal de sécurité séparé |
| Séparation des tâches (RG-19) | Service `ExigeUtilisateurDifferent` appliqué aux validations du tableau §9.3, règles lisibles depuis `parametres` |
| Périmètre d'emplacement (ADM-13) | Portée globale Eloquent + Policy, + filtre portefeuille pour le commercial. En-tête actuel `X-Site-Id` renommé `X-Emplacement-Id` |
| Verrou optimiste | Colonne `version` sur `chassis`, `commandes_client`, `factures`, `transferts` |
| Idempotence | En-tête `Idempotency-Key` sur vente et règlement (table `cles_idempotence`) |
| Numérotation | Table `sequences` verrouillée ; remplace le trait `GenereNumero` actuel (qui peut produire des trous) |
| Événements et workers | Table d'envoi + files Redis ; workers pour e-MECeF, PDF, rapports, notifications, détection d'anomalies. Planificateur Laravel pour les contrôles nocturnes |
| e-MECeF | Client dédié, deux temps dépôt puis confirmation, **toujours relire l'état avant de redéposer** (EF-FAC-08), journal des échanges, simulateur local pour le développement |
| Documents | Stockage S3 (MinIO en dev), versions, SHA-256, liens signés courts. Le `disk('public')` actuel ne convient plus |
| Notifications | En application (SSE) et e-mail ; SMS en option |
| API | `/api/v1`, codes d'erreur métier (`CHASSIS_DEJA_RESERVE`…), pagination, OpenAPI |

### 3.5 Rôles et permissions

Rôles livrés : `admin`, `direction`, `responsable_commercial`, `commercial`, `comptabilite_caisse`, `magasin`, `controleur`, `boutique`, selon la matrice §9.1.
Permissions par action et module : `<module>.voir|creer|modifier|valider|annuler|exporter`, plus les permissions spéciales : `prix_achat.voir`, `marges.voir`, `creances.voir`, `depenses.voir`, `stock.modifier`, `inventaire.cloturer`, `anomalies.voir`, `anomalies.cloturer`.
Reprise des anciens rôles : `direction_generale` → `direction` ; `responsable_commercial_groupe` → `responsable_commercial` ; `vendeur_moto`, `manager_marque` → `commercial` / `boutique` ; `magasinier` → `magasin` ; `caissier`, `responsable_achats` → `comptabilite_caisse` ; `responsable_pieces_detachees`, `vendeur_pieces_detachees` supprimés ; `controleur` nouveau.

---

## 4. Audit de l'existant : conserver, adapter, remplacer, retirer

### Backend

| Élément actuel | Verdict | Détail |
|---|---|---|
| Auth Sanctum cookie, 2FA TOTP, journal des échecs | **Adapter** | Ajouter verrouillage après 5 échecs, 2FA obligatoire pour admin, direction et comptabilité, lien d'activation 24 h, Argon2id, expiration sur inactivité |
| Users, rôles, permissions (CRUD, assistant de création) | **Adapter** | Remplacer sites + marques + département par emplacements ; nouveau catalogue de permissions ; copie de rôle (ADM-18) |
| `AuditLogger` + `audit_logs` | **Adapter** | Même contrat, nouveau stockage en ajout seul avec valeurs avant/après |
| `ApiException`, `ApiResponse`, FormRequests, Resources, Services | **Conserver** | Le patron (controller → service → audit) reste la règle |
| `Site`, `SiteService`, `departements` | **Remplacer** | Par `emplacements` (types, mode A/B, rattachement). `departements` retirés (pas dans le SRS 1.0) |
| `Article`, `Category`, `Brand`, `article_brand` | **Remplacer / retirer** | Par `modeles` + `prix_historique`. Catégories, conditionnement, prix gros/revendeur/DAO, compatibilité marques : retirés |
| `Tiers`, `TiersContact`, `FormeJuridique`, `Pays` | **Adapter / scinder** | `clients` et `fournisseurs` ; contacts conservés ; IFU, plafond, délai, commercial, dépôt-vente ajoutés. `FormeJuridique` et `Pays` : à garder seulement si utiles au fournisseur (devise, pays) |
| `CommandeAchat*`, `BonDeCommandeService` (PDF) | **Remplacer** | Par commande fournisseur à 8 statuts + arrivages + frais. Le PDF du bon de commande (DomPDF) est réutilisable. Circuit de validation par seuil : retiré (le SRS n'en prévoit pas) |
| `BonReception*` | **Remplacer** | Réception par scan de châssis, validation par un second utilisateur, PV PDF |
| `BonRetour*` (retour fournisseur) | **Retirer** | Absent du SRS 1.0. Un châssis abîmé entre BLOQUÉ |
| `Stock`, `MouvementStock` (par article), `StockService` (ajustement libre) | **Remplacer** | Par `chassis` + `mouvements_stock` par châssis. L'ajustement libre est **interdit** (EF-STK-04), il passe par AJU |
| `MotoUnite` (`motos_unitaires`) | **Remplacer** | Prototype à 3 statuts : devient `chassis` à 12 états |
| `TransfertStock*` | **Adapter** | Le circuit en trois temps existe ; il manque la liste de châssis obligatoire, le scan, la réception partielle, l'anomalie A12 |
| `Inventaire*` | **Remplacer** | Inventaire par châssis, six constats, PV, hors ligne, aucune correction automatique |
| Seeders, factories, tests | **Réécrire** | Les tests par article n'ont plus de sens ; les tests d'auth, users, rôles restent |

### Frontend

| Élément actuel | Verdict |
|---|---|
| Composants partagés (`pagination-bar`, `searchable-select`, `multi-select`, `confirm-dialog`, `toast-host`), `permission.guard`, intercepteurs, `AuthService` | **Conserver** |
| Patron liste / formulaire à onglets / détail (Tiers, Article, Commande) | **Conserver comme gabarit** des nouveaux écrans |
| Utilisateurs, Rôles | **Adapter** (emplacements, nouvelles permissions) |
| Référentiels : Sites, Départements, Marques, Catégories, Articles, Tiers, Formes juridiques, Pays | **Remplacer** par Emplacements, Modèles, Clients, Fournisseurs, Paramètres |
| Achats (commandes, réceptions, retours) | **Remplacer** par Dossier d'importation, Arrivages, Réceptions en mode scan |
| Stocks (liste, mouvements, valorisation, transferts, inventaires) | **Remplacer** par Stock global, par magasin, par boutique, Châssis, Transferts en mode scan |
| Stubs vides `clients`, `facturation`, `remises`, `ventes-motos`, `ventes-pieces`, `multi-sites`, `reporting` | **Supprimer** les stubs inutiles ; recréer selon le menu §14.5 |
| Pages de démonstration CoreUI (components, forms, icons, widgets, charts) | **Supprimer** à la purge finale (déjà prévu) |
| Menu latéral | **Refaire** selon §14.5 : Tableau de bord, Commercial, Stock et logistique, Dépôt-vente, Comptabilité, Analyses, Contrôle, Documents, Administration |

Nouveau côté front : mode scan (caméra), PWA avec service worker et IndexedDB pour le pointage d'inventaire hors ligne, notifications SSE, recherche globale, tableaux de bord par profil, formats Bénin (JJ/MM/AAAA, espace des milliers, zones tactiles 44 px).

---

## 5. Plan d'implémentation

Le plan suit les versions et sprints du SRS (§13) : S0 à S15, V1 à V4. L'existant fait gagner du temps sur le socle, pas sur le cœur métier.

### Phase 0 — Re-fondation (avant S1)
1. Snapshot git du code actuel (tag `legacy-srs-v2`), backend initialisé en dépôt.
2. Passage à PostgreSQL (dev, tests), `.env`, `phpunit.xml`, CI.
3. Migration de base propre : on garde users, permissions, tokens, cache, jobs ; on supprime les migrations métier héritées ; seeders vides.
4. Retrait du code pièces détachées, marques, départements, retours fournisseurs, catégories (D4, D5).
5. Réorganisation en `app/Domain/<Module>` ; préfixe `/api/v1` ; OpenAPI (Scribe ou Scramble).
6. Socle transverse du §3.4 : `journal_audit` ajout seul, `sequences`, séparation des tâches, périmètre d'emplacement, idempotence, outbox.
7. Menu et routes du §14.5 ; suppression des stubs.

### V1 « Stock tracé » (S1 à S3)

| Sprint | Stories | Backend | Frontend | Réutilisation |
|---|---|---|---|---|
| S1 Socle | US-001 à 005, 007, 008, 155 | Users + emplacements, 2FA par rôle, verrouillage, permissions par action, mode A/B, paramètres, journal d'audit, notifications, refus de suppression | Utilisateurs, Rôles, Emplacements, Paramètres, cloche de notifications | Forte (auth, users, rôles, audit) |
| S2 Catalogue et importation | US-010 à 013, 020 à 023 | Modèles et prix historisés, châssis (format, unicité, correction autorisée), commande fournisseur, arrivages, frais, 8 statuts, import colisage | Catalogue, Fiche châssis, Dossier d'arrivage à onglets | Moyenne (gabarits, PDF) |
| S3 Réception, stock, transferts | US-006, 024 à 027, 040 à 045 | Réception par scan et validation, **`ChassisService` + mouvements**, vues de stock, transferts à trois temps, A12, import Excel initial | Saisie en mode scan, Stock global/magasin/boutique, Transferts | Faible (cœur neuf) |
Jalon V1 : stock initial importé par châssis, rapproché d'un inventaire physique au magasin et dans les deux boutiques (R6, R8 partiel).

### V2 « Vente et encaissement » (S4 à S8)

| Sprint | Stories | Contenu |
|---|---|---|
| S4 Clients et commandes | US-050 à 053, 060 à 064, 067 | Clients/revendeurs, position (créance, encours, historique), commandes, affectation de châssis avec index unique, contrôles prix minimum, remise, plafond |
| S5 Facturation e-MECeF (test) | US-070 à 072, 075, 152 | Facture par châssis, client e-MECeF, normalisation en deux temps, reprise sans doublon, journal DGI, recherche par châssis |
| S6 Livraisons et vente rapide | US-065, 066, 080 à 085 | Préparation, scan au chargement, livraison partielle 40/27, blocage sans facture, vente Client Divers modes A et B |
| S7 Règlements | US-090 à 097 | Espèces + AIB + décharge, cycle du chèque, virement, Mobile Money, multi-factures, inversion |
| S8 Créances et avoirs | US-054, 073, 074, 098 à 100 | Balance âgée, avoir normalisé, relances, passage nocturne des échéances, dossier de validation DGI |
Jalon V2 : logiciel validé par la DGI, première facture normalisée réelle (chemin critique du projet). Recette R1 à R5, R9 à R11.

### V3 « Dépôt-vente et comptabilité » (S9 à S11)
S9 dépôt-vente (US-110 à 114) ; S10 inventaires hors ligne et ajustements (US-120 à 125, PWA) ; S11 coût de revient, caisse, dépenses, stock à date (US-030 à 033, 046, 130 à 135). Recette R7, R8.

### V4 « Contrôle et pilotage » (S12 à S14)
S12 anomalies A01 à A13 (US-140 à 145) ; S13 documents, audit consultable, tableaux de bord (US-150, 151, 153, 154, 160, 161) ; S14 rapports et analyses (US-162 à 166). Recette R12.

### S15 — Stabilisation
Test de charge (ENF-06), test d'intrusion, formation, bascule site par site.

### Définition de « terminé » appliquée à chaque story
Critères d'acceptation démontrés ; tests automatiques des règles touchées ; permissions et périmètre testés **par appel direct à l'API** ; entrée au journal avec valeurs avant/après ; documentation d'API à jour ; écran vérifié sur ordinateur et téléphone.

---

## 6. Points ouverts du SRS qui bloquent du travail

| Point (§14.4) | Bloque |
|---|---|
| Format des numéros de châssis par modèle, code-barres | S2/S3 : contrôle de format et scan (prévoir un champ regex par modèle, valeur à fournir) |
| Seuils : remise par rôle, délais chèque/transfert/normalisation, écart d'inventaire | S1 (paramètres) puis S4, S7 |
| Groupe de taxation TVA des motos, AIB (cas, taux), AIB payé en douane | S5, S7, S11 |
| Un IFU ou un par boutique | S5 |
| Méthode de répartition des frais d'importation | S11 (S2 pour la saisie) |
| Qui crée les commandes fournisseurs | S2 : suivre la matrice §9.1 (Admin et Comptabilité créent, Direction valide) en attendant |
| Acompte avant facturation, trop-perçu après avoir | S7, S8 |
| Hébergement, logiciel comptable cible | S15, S11 |

Autres risques : accès au jeton e-MECeF de test à demander dès la semaine 1 ; Angular contre React à confirmer avec le client ; ENF-03 (200 châssis validés en moins de 10 s) à valider très tôt sur PostgreSQL.

---

## 7. Recette automatisée

Chaque scénario R1 à R12 du §14.1 devient un test fonctionnel Laravel de bout en bout (par exemple R1 : réception de 100 châssis, commande de 20, facture, livraison, paiement ; attendu 100 physique, 20 réservé, 80 disponible, puis 80 physique, créance soldée). Couverture visée : 70 % minimum sur stock, facturation et règlements (ENF-16).

---

## 8. Prochaine étape proposée

1. Valider D1 à D6 (surtout PostgreSQL, Angular, et le retrait des pièces détachées).
2. Autoriser le snapshot git du code actuel.
3. Démarrer la Phase 0, puis S1.
