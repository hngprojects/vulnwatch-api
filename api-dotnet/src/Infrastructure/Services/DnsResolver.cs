using Application.Interfaces;
using DnsClient;
using DnsClient.Protocol;
using Microsoft.Extensions.Logging;

namespace Infrastructure.Services;

public sealed class DnsResolver(LookupClient dns, ILogger<DnsResolver> logger) : IDnsResolver
{
    public async Task<IReadOnlyList<string>> GetTxtRecords(string host, CancellationToken ct)
    {
        var result = await dns.QueryAsync(host, QueryType.TXT, cancellationToken: ct);

        return result.Answers
            .TxtRecords()
            .SelectMany(r => r.Text)
            .ToList();
    }

    public async Task<bool> CheckTxtRecord(string host, string expectedValue, CancellationToken ct)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(host))
                throw new ArgumentException("Host is required", nameof(host));

            var result = await dns.QueryAsync(host, QueryType.TXT, cancellationToken: ct);

            var txtRecords = result.Answers.TxtRecords();

            logger.LogInformation("TXT records found for {Host}: {Records}",
                host,
                string.Join(", ", txtRecords.Select(r => string.Join("", r.Text))));

            foreach (var record in txtRecords)
            {
                var value = string.Join("", record.Text);

                if (value.Equals(expectedValue, StringComparison.OrdinalIgnoreCase))
                    return true;
            }
            return false;
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Error checking DNS TXT record for host: {Host}", host);
            return false;
        }
    }
}