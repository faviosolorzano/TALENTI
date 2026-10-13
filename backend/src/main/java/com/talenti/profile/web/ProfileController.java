package com.talenti.profile.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.profile.ProfileView;
import com.talenti.profile.domain.ProfileRules;
import com.talenti.profile.service.ProfileService;

/**
 * Perfil del estudiante (spec 006, sección 6). El id de la persona sale siempre de la sesión: ninguna ruta recibe
 * el id de un usuario (006-04). El rol y {@code Cache-Control: no-store} los impone {@link StudentOnlyInterceptor}
 * a toda la ruta. Cada escritura devuelve el perfil completo, con el porcentaje de avance ya recalculado (006-11).
 */
@RestController
@RequestMapping(ProfileWebConfig.PROFILE)
class ProfileController {

	private final ProfileService profiles;

	ProfileController(ProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	ProfileView get(@AuthenticationPrincipal CurrentUser user) {
		return profiles.current(user.id());
	}

	@PutMapping("/about")
	ProfileView saveAbout(@AuthenticationPrincipal CurrentUser user, @RequestBody Requests.About body) {
		return profiles.saveAbout(user.id(), body.displayName(), body.about());
	}

	@PutMapping("/academic")
	ProfileView saveAcademic(@AuthenticationPrincipal CurrentUser user, @RequestBody Requests.Academic body) {
		return profiles.saveAcademic(user.id(), body.career(), body.institute(), Requests.toInt(body.cycle()));
	}

	@PutMapping("/skills")
	ProfileView replaceSkills(@AuthenticationPrincipal CurrentUser user, @RequestBody Requests.Skills body) {
		List<ProfileRules.SkillInput> skills = body.skills() == null ? null
				: body.skills().stream()
						.map(item -> item == null ? null
								: new ProfileRules.SkillInput(item.slug(), Requests.toInt(item.level())))
						.toList();
		return profiles.replaceSkills(user.id(), skills);
	}

	@PutMapping("/availability")
	ProfileView saveAvailability(@AuthenticationPrincipal CurrentUser user,
			@RequestBody Requests.Availability body) {
		return profiles.saveAvailability(user.id(), Requests.toInt(body.hoursPerWeek()), body.acceptedModalities(),
				body.location());
	}

	@PostMapping("/projects")
	ResponseEntity<ProfileView> addProject(@AuthenticationPrincipal CurrentUser user,
			@RequestBody Requests.Project body) {
		ProfileView profile = profiles.addProject(user.id(), body.name(), body.description(), body.technologies());
		return ResponseEntity.status(HttpStatus.CREATED).body(profile);
	}

	@PutMapping("/projects/{id}")
	ProfileView updateProject(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestBody Requests.Project body) {
		return profiles.updateProject(user.id(), id, body.name(), body.description(), body.technologies());
	}

	@DeleteMapping("/projects/{id}")
	ProfileView deleteProject(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		return profiles.deleteProject(user.id(), id);
	}

}
