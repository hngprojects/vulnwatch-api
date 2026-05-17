package com.vulnwatch.worker.scanners.dns.models;

/**
 * IP address metadata
 *
 * @param ip
 * @param asn
 * @param org
 * @param country
 */
public record IpMetadata(String ip, int asn, String org, String country) {}
