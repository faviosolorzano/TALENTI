package com.talenti.profile.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/**
 * 404 {@code project-not-found}: el proyecto no existe, es de otra persona o el identificador está mal formado. Los
 * tres casos responden igual para no revelar qué proyectos existen (006-04).
 */
public class ProjectNotFoundException extends ApiException {

	public static final String CODE = "project-not-found";

	public ProjectNotFoundException() {
		super(HttpStatus.NOT_FOUND, CODE);
	}

}
