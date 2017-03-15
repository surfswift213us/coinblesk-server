package com.coinblesk.server.config;

import static springfox.documentation.spi.DocumentationType.SWAGGER_2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableSwagger2
public class SwaggerConfig {

	@Bean
	public Docket api() {
		return new Docket(SWAGGER_2).select()
									.apis(RequestHandlerSelectors.any())
									.paths(PathSelectors.regex("/v[12]/.*"))
									.build()
									.apiInfo(apiInfo());
	}

	private ApiInfo apiInfo() {
		ApiInfo apiInfo = new ApiInfo(
				"Coinblesk Server REST API",
				"REST API for the Android App and the Web Interface",
				"",
				"",
				new Contact(
						"Communication Systems Research Group CSG, University of Zurich",
						"https://bitcoin.csg.uzh.ch",
						"bocek@ifi.uzh.ch"),
				"",
				"");

		return apiInfo;
	}

}