package br.com.paulorcv.rinha.dto;

import lombok.Data;

@Data
public class ServiceHealthResponse {
	private boolean failing;
	private int minResponseTime;
}