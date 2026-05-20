package com.vulnwatch.worker.engine;

import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.ScanJob;
import com.vulnwatch.worker.model.payload.DnsPayload;

import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DnsEngine implements ScanEngine {

    @Override
    public String surface() { return "Dns"; }

    @Override
    public EngineResult run(ScanJob job) {
        String domain = job.domainName();
        Map<String, String> rawRecords = new HashMap<>();
        List<String> issues = new ArrayList<>();
        boolean hasSPF = false;
        boolean hasDMARC = false;
        boolean hasMX = false;

        try {
            InitialDirContext ctx = new InitialDirContext();

            try {
                Attributes txt = ctx.getAttributes("dns:/" + domain, new String[]{"TXT"});
                String txtRecords = txt.toString();
                hasSPF = txtRecords.contains("v=spf1");
                if (!hasSPF) issues.add("No SPF record found");
                rawRecords.put("txt", txtRecords);
            } catch (Exception e) {
                issues.add("Could not retrieve TXT records: " + e.getMessage());
            }

            try {
                Attributes dmarc = ctx.getAttributes("dns:/_dmarc." + domain, new String[]{"TXT"});
                hasDMARC = true;
                rawRecords.put("dmarc", dmarc.toString());
            } catch (Exception e) {
                issues.add("No DMARC record found at _dmarc." + domain);
            }

            try {
                Attributes mx = ctx.getAttributes("dns:/" + domain, new String[]{"MX"});
                hasMX = true;
                rawRecords.put("mx", mx.toString());
            } catch (Exception e) {
                issues.add("No MX records found");
            }

            return new EngineResult("Dns", true, null,
                    new DnsPayload(hasSPF, hasDMARC, hasMX, issues, rawRecords));

        } catch (Exception e) {
            return EngineResult.failure("Dns", "DNS lookup failed: " + e.getMessage());
        }
    }
}