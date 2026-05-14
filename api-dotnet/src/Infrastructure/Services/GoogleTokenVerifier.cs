using System.IdentityModel.Tokens.Jwt;
using Application.Interfaces;
using Domain.Common;
using Microsoft.Extensions.Configuration;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace Infrastructure.Services;

public class GoogleTokenVerifier : IGoogleTokenVerifier
{
    private static readonly string[] ValidIssuers =
    [
        "https://accounts.google.com",
        "accounts.google.com"
    ];

    private readonly IConfiguration _configuration;
    private readonly IConfigurationManager<OpenIdConnectConfiguration> _configurationManager;
    private readonly JwtSecurityTokenHandler _tokenHandler = new() { MapInboundClaims = false };

    public GoogleTokenVerifier(IConfiguration configuration)
    {
        _configuration = configuration;
        _configurationManager = new ConfigurationManager<OpenIdConnectConfiguration>(
            "https://accounts.google.com/.well-known/openid-configuration",
            new OpenIdConnectConfigurationRetriever(),
            new HttpDocumentRetriever { RequireHttps = true });
    }

    public async Task<Result<GoogleUserInfo>> VerifyIdTokenAsync(string idToken, CancellationToken ct)
    {
        var clientId = _configuration["Authentication:Google:ClientId"];
        if (string.IsNullOrWhiteSpace(clientId))
            return Result<GoogleUserInfo>.Failure(Error.Internal("Google authentication is not configured."));

        OpenIdConnectConfiguration googleConfiguration;

        try
        {
            googleConfiguration = await _configurationManager.GetConfigurationAsync(ct);
        }
        catch
        {
            return Result<GoogleUserInfo>.Failure(
                Error.Internal("Unable to load Google's signing configuration."));
        }

        var validationParameters = new TokenValidationParameters
        {
            ValidateIssuerSigningKey = true,
            IssuerSigningKeys = googleConfiguration.SigningKeys,
            ValidateIssuer = true,
            ValidIssuers = ValidIssuers,
            ValidateAudience = true,
            ValidAudience = clientId,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromMinutes(2)   // Was 1
        };

        try
        {
            var principal = _tokenHandler.ValidateToken(idToken, validationParameters, out _);
            var subject = principal.FindFirst(JwtRegisteredClaimNames.Sub)?.Value;
            var email = principal.FindFirst(JwtRegisteredClaimNames.Email)?.Value;
            var emailVerifiedClaim = principal.FindFirst("email_verified")?.Value;
            var emailVerified = bool.TryParse(emailVerifiedClaim, out var parsedEmailVerified) &&
                                parsedEmailVerified;

            if (string.IsNullOrWhiteSpace(subject) || string.IsNullOrWhiteSpace(email))
            {
                return Result<GoogleUserInfo>.Failure(
                    Error.Unauthorized("Google token is missing required claims."));
            }

            return Result<GoogleUserInfo>.Success(new GoogleUserInfo(subject, email, emailVerified));
        }
        catch (SecurityTokenExpiredException)
        {
            return Result<GoogleUserInfo>.Failure(Error.Unauthorized("Google token has expired."));
        }
        catch (SecurityTokenInvalidAudienceException)
        {
            return Result<GoogleUserInfo>.Failure(Error.Unauthorized("Invalid audience (Client ID mismatch)."));
        }
        catch (SecurityTokenInvalidIssuerException)
        {
            return Result<GoogleUserInfo>.Failure(Error.Unauthorized("Invalid issuer."));
        }
        catch (Exception ex) when (ex is ArgumentException || ex is SecurityTokenException)
        {
            return Result<GoogleUserInfo>.Failure(Error.Unauthorized($"Invalid Google token: {ex.Message}"));
        }
        // catch (ArgumentException)
        // {
        //     return Result<GoogleUserInfo>.Failure(Error.Unauthorized("Invalid Google token."));
        // }
        // catch (SecurityTokenException)
        // {
        //     return Result<GoogleUserInfo>.Failure(Error.Unauthorized("Invalid Google token."));
        // }
    }
}
