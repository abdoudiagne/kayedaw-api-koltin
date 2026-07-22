package com.kayedaw.admin

import com.kayedaw.preference.PreferenceSeanceRepository
import com.kayedaw.seance.SeanceRepository
import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ SUPPRESSION EN MASSE — ce que ces tests protègent                       │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * L'intérêt n'est pas « plusieurs suppressions fonctionnent » — cela découle de
 * la boucle. Ce qui mérite un test, c'est que les garde-fous PAR COMPTE
 * survivent au traitement par lot, et que le refus de l'un n'empêche pas les
 * autres de partir.
 */
class SuppressionEnMasseTest {

    private val utilisateurRepository = mockk<UtilisateurRepository>(relaxed = true)
    private val seanceRepository = mockk<SeanceRepository>(relaxed = true)
    private val preferenceRepository = mockk<PreferenceSeanceRepository>(relaxed = true)

    private val encodeur = mockk<PasswordEncoder>(relaxed = true)

    private val service = AdminService(
        utilisateurRepository, seanceRepository, preferenceRepository, encodeur)

    private fun compte(id: Long, email: String, role: Role = Role.USER) =
        Utilisateur(email = email, motDePasse = "hash", nom = "N$id",
            villeParDefaut = "Lille", role = role, id = id)

    @Test
    fun `supprime tout le lot quand rien ne s y oppose`() {
        val comptes = listOf(compte(1, "a@x.fr"), compte(2, "b@x.fr"), compte(3, "c@x.fr"))
        comptes.forEach { every { utilisateurRepository.findById(it.id!!) } returns Optional.of(it) }

        val rapport = service.supprimerEnMasse(listOf(1, 2, 3), "admin@x.fr")

        assertThat(rapport.supprimes).containsExactly(1, 2, 3)
        assertThat(rapport.refuses).isEmpty()
        // Chaque compte emporte ses dépendances : la contrainte de clé
        // étrangère l'exige, et l'oublier ne se verrait qu'à l'exécution.
        comptes.forEach {
            verify { seanceRepository.deleteByUtilisateurId(it.id!!) }
            verify { preferenceRepository.deleteByUtilisateurId(it.id!!) }
        }
    }

    @Test
    fun `refuse son propre compte SANS bloquer les autres du lot`() {
        val soi = compte(1, "admin@x.fr", Role.ADMIN)
        val autre = compte(2, "b@x.fr")
        every { utilisateurRepository.findById(1) } returns Optional.of(soi)
        every { utilisateurRepository.findById(2) } returns Optional.of(autre)

        val rapport = service.supprimerEnMasse(listOf(1, 2), "admin@x.fr")

        // C'est tout l'intérêt du rapport : un refus est PARTIEL, pas bloquant
        assertThat(rapport.supprimes).containsExactly(2)
        assertThat(rapport.refuses).singleElement()
            .satisfies({ assertThat(it.motif).isEqualTo("AUTO_SUPPRESSION") })
        verify(exactly = 0) { utilisateurRepository.delete(soi) }
    }

    /**
     * LE test du lot : trois administrateurs sélectionnés d'un coup.
     *
     * `countByRole` est une requête SQL. Sans `flush()` entre deux suppressions,
     * elle renvoie encore 3 à chaque passage, les trois refus n'ont jamais lieu
     * et l'application se retrouve SANS AUCUN administrateur — irrattrapable
     * sans intervention directe en base.
     */
    @Test
    fun `laisse toujours un administrateur, meme si le lot les contient tous`() {
        val admins = listOf(
            compte(1, "a1@x.fr", Role.ADMIN),
            compte(2, "a2@x.fr", Role.ADMIN),
            compte(3, "a3@x.fr", Role.ADMIN)
        )
        admins.forEach { every { utilisateurRepository.findById(it.id!!) } returns Optional.of(it) }

        // Le compte d'administrateurs DÉCROÎT au fil des suppressions : c'est ce
        // que le flush() rend visible à la requête.
        every { utilisateurRepository.countByRole(Role.ADMIN) } returnsMany listOf(3, 2, 1)

        val rapport = service.supprimerEnMasse(listOf(1, 2, 3), "autre@x.fr")

        assertThat(rapport.supprimes).containsExactly(1, 2)
        assertThat(rapport.refuses).singleElement()
            .satisfies({
                assertThat(it.id).isEqualTo(3)
                assertThat(it.motif).isEqualTo("DERNIER_ADMINISTRATEUR")
            })
    }

    @Test
    fun `un identifiant repete ne produit pas de faux refus`() {
        val cible = compte(1, "a@x.fr")
        every { utilisateurRepository.findById(1) } returns Optional.of(cible)

        val rapport = service.supprimerEnMasse(listOf(1, 1, 1), "admin@x.fr")

        // Sans distinct(), les deuxième et troisième passages renverraient
        // « Introuvable » — un refus qui ne décrit aucune réalité.
        assertThat(rapport.supprimes).containsExactly(1)
        assertThat(rapport.refuses).isEmpty()
    }

    @Test
    fun `un identifiant inexistant est rapporte sans interrompre le lot`() {
        every { utilisateurRepository.findById(9) } returns Optional.empty()
        every { utilisateurRepository.findById(2) } returns Optional.of(compte(2, "b@x.fr"))

        val rapport = service.supprimerEnMasse(listOf(9, 2), "admin@x.fr")

        assertThat(rapport.supprimes).containsExactly(2)
        assertThat(rapport.refuses).singleElement()
            .satisfies({ assertThat(it.motif).isEqualTo("INTROUVABLE") })
    }
}

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ ÉDITION ADMINISTRATIVE — les garde-fous, pas les setters                │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Écrire un nom dans une entité managée n'a pas besoin d'un test. Ce qui en
 * mérite un, ce sont les refus : ils protègent l'administrabilité même de
 * l'application, et un blocage mal borné ne se découvre qu'une fois enfermé
 * dehors.
 */
class EditionAdministrativeTest {

    private val utilisateurRepository = mockk<UtilisateurRepository>(relaxed = true)
    private val encodeur = mockk<PasswordEncoder>(relaxed = true)
    private val service = AdminService(
        utilisateurRepository, mockk(relaxed = true), mockk(relaxed = true), encodeur)

    private fun compte(id: Long, email: String, role: Role = Role.USER, actif: Boolean = true) =
        Utilisateur(email = email, motDePasse = "hash", nom = "Ancien",
            villeParDefaut = "Lille", role = role, actif = actif, id = id)

    @Test
    fun `modifie le nom et la ville, en coupant les espaces`() {
        val cible = compte(1, "a@x.fr")
        every { utilisateurRepository.findById(1) } returns Optional.of(cible)

        val resultat = service.modifier(1, ModifierUtilisateurRequest("  Nouveau  ", " Lyon "))

        assertThat(resultat).isEqualTo(ResultatAdmin.Applique)
        assertThat(cible.nom).isEqualTo("Nouveau")
        assertThat(cible.villeParDefaut).isEqualTo("Lyon")
    }

    @Test
    fun `refuse un nom vide plutot que d ecraser l existant`() {
        val cible = compte(1, "a@x.fr")
        every { utilisateurRepository.findById(1) } returns Optional.of(cible)

        val resultat = service.modifier(1, ModifierUtilisateurRequest("   ", "Lyon"))

        assertThat(resultat).isInstanceOf(ResultatAdmin.Refuse::class.java)
        assertThat(cible.nom).isEqualTo("Ancien")     // rien n'a bougé
    }

    @Test
    fun `reinitialise le mot de passe SANS demander l ancien`() {
        val cible = compte(1, "a@x.fr")
        every { utilisateurRepository.findById(1) } returns Optional.of(cible)
        every { encodeur.encode("nouveau-mot-de-passe") } returns "haché"

        val resultat = service.reinitialiserMotDePasse(1, "nouveau-mot-de-passe")

        assertThat(resultat).isEqualTo(ResultatAdmin.Applique)
        assertThat(cible.motDePasse).isEqualTo("haché")
    }

    @Test
    fun `refuse un mot de passe trop court`() {
        every { utilisateurRepository.findById(1) } returns Optional.of(compte(1, "a@x.fr"))

        // « abc » : 4 caractères. « court » en fait 5 et serait désormais ACCEPTÉ.
        val resultat = service.reinitialiserMotDePasse(1, "abc")

        assertThat(resultat).isInstanceOf(ResultatAdmin.Refuse::class.java)
        verify(exactly = 0) { encodeur.encode(any<String>()) }
    }

    @Test
    fun `bloque puis debloque un compte`() {
        val cible = compte(1, "a@x.fr")
        every { utilisateurRepository.findById(1) } returns Optional.of(cible)

        service.bloquer(1, actif = false, emailDemandeur = "admin@x.fr")
        assertThat(cible.actif).isFalse()

        // Le déblocage n'a AUCUN garde-fou : rendre l'accès ne peut rien casser
        service.bloquer(1, actif = true, emailDemandeur = "admin@x.fr")
        assertThat(cible.actif).isTrue()
    }

    @Test
    fun `refuse de se bloquer soi-meme`() {
        val soi = compte(1, "admin@x.fr", Role.ADMIN)
        every { utilisateurRepository.findById(1) } returns Optional.of(soi)

        val resultat = service.bloquer(1, actif = false, emailDemandeur = "admin@x.fr")

        assertThat(resultat).isInstanceOf(ResultatAdmin.Refuse::class.java)
        assertThat(soi.actif).isTrue()
    }

    /**
     * Le compte d'administrateurs porte sur les comptes ACTIFS : un admin déjà
     * bloqué n'administre plus rien. S'appuyer sur `countByRole` laisserait
     * bloquer le dernier administrateur réellement opérationnel.
     */
    @Test
    fun `refuse de bloquer le dernier administrateur ACTIF`() {
        val dernier = compte(1, "a1@x.fr", Role.ADMIN)
        every { utilisateurRepository.findById(1) } returns Optional.of(dernier)
        every { utilisateurRepository.countByRoleAndActifTrue(Role.ADMIN) } returns 1

        val resultat = service.bloquer(1, actif = false, emailDemandeur = "autre@x.fr")

        assertThat(resultat).isInstanceOf(ResultatAdmin.Refuse::class.java)
        assertThat((resultat as ResultatAdmin.Refuse).motif).isEqualTo("DERNIER_ADMINISTRATEUR")
        assertThat(dernier.actif).isTrue()
    }
}
