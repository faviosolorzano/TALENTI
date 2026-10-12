package com.talenti.identity;

/**
 * Finalidad de un permiso (RF-47, RN-15). {@code TERMS} y {@code ACCOUNT_DATA} se aceptan al registrarse;
 * {@code CV_AI} y {@code VOICE_AI} los piden después, en el momento, las rebanadas del lector de CV y de la voz
 * (aquí solo queda lista la estructura, 001-27).
 */
public enum ConsentPurpose {

	TERMS,
	ACCOUNT_DATA,
	CV_AI,
	VOICE_AI

}
