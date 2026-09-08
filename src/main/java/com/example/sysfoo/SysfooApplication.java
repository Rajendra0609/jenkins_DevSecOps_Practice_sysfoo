package com.example.sysfoo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * UPDATE: extends SpringBootServletInitializer, paired with
 * <packaging>war</packaging> and the provided-scope spring-boot-starter-tomcat
 * dependency in pom.xml. This is what lets the exact same build artifact run
 * two ways:
 *
 *   1) java -jar target/sysfoo-0.0.1-SNAPSHOT.war
 *      Standalone, embedded Tomcat — identical to how the old plain jar ran.
 *      main() below is the entry point for this mode; configure() is unused.
 *
 *   2) Deployed into an external Tomcat's webapps/ directory.
 *      An external servlet container looks for a WAR that implements
 *      SpringBootServletInitializer and calls configure() itself instead of
 *      main() — that's the standard hook Spring Boot documents for traditional
 *      deployment (see spring.io "Traditional Deployment").
 *
 * IMPORTANT if deploying to an external Tomcat: the frontend
 * (static/index.html, login.html) calls absolute paths like /todos,
 * /api/auth/me, /actuator/health — it assumes the app is served from the
 * ROOT context ("/"), not a sub-path. Deploy this WAR as ROOT.war (renaming
 * it, or configuring Tomcat's context path), not as sysfoo.war — Tomcat
 * would otherwise serve it under /sysfoo/*, and every fetch() call in the
 * frontend would 404 against the real root.
 */
@SpringBootApplication
public class SysfooApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(SysfooApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(SysfooApplication.class);
    }
}
