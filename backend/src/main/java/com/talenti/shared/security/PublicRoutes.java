package com.talenti.shared.security;

import java.util.List;

/**
 * Cada módulo declara aquí sus rutas públicas, así la configuración de seguridad (que vive en shared) no
 * depende de ningún módulo. Todo lo que no se declare exige sesión (001-13).
 */
public interface PublicRoutes {

	List<PublicRoute> publicRoutes();

}
