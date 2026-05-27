using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Microsoft.Extensions.Logging;
using System.Security.Cryptography;
using System.Text;

namespace Web.Workers.Monitoring;

public sealed class OwnershipCheckService(
    IDnsResolver dnsResolver,
    // IDomainRepository domainRepo,
    IConfiguration config,
    ILogger<OwnershipCheckService> logger)
{
    public async Task CheckAsync(
        DomainSettings settings,
        CancellationToken ct)
    {
        // Only re-check if DNS lookup is enabled — mirrors VerifyDomainHandler
        if (!config.GetValue<bool>("Dns:Lookup"))
            return;

        var domain = settings.Domain;

        // Nothing to validate against if token was cleared on verification
        // Re-verification requires a token — skip silently
        if (domain.VerificationToken is null)
            return;

        var txtHost  = $"_vulnwatch-verify.{domain.DomainName}";
        var txtValues = await dnsResolver.GetTxtRecords(txtHost, ct);

        var expectedHash = domain.VerificationToken;
        var stillValid   = txtValues.Any(v =>
        {
            var hash = Convert.ToBase64String(
                SHA256.HashData(Encoding.UTF8.GetBytes(v)));
            return hash == expectedHash;
        });

        if (stillValid)
        {
            logger.LogDebug(
                "Ownership check passed for {Domain}", domain.DomainName);
            return;
        }

        // TXT record removed — flag domain, do not auto-revoke
        // Revoking automatically could be disruptive if DNS propagation
        // is just slow. Surface it as a warning instead.
        logger.LogWarning(
            "Ownership TXT record no longer found for {Domain}",
            domain.DomainName);

        // Future: dispatch a DomainOwnershipFailedEvent to alert the user
        // For now log and let the next worker cycle retry
    }
}