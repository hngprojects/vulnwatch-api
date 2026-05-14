

namespace Application.Helpers;

public class UrlConfig
{
    public string VulnWatchBaseUrl { get; set; } = default!;
    public string EmailEndpoint { get; set; } = default!;
    public string SubscriptionKey { get; set; } = default!;



}

public class JwtConfig
{
    public const string SectionName = "Jwt";

    public string SecretKey { get; init; } = string.Empty;
    public string Issuer { get; init; } = "vulnwatch-api";
    public string Audience { get; init; } = "vulnwatch-client";
    public int AccessTokenExpiryMinutes { get; init; } = 15;
    public int RefreshTokenExpiryDays { get; init; } = 7;
}