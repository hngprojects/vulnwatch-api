using System.Security.Claims;
using System.Text;
using Application.Helpers;
using Application.Interfaces;
using Domain.Common;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using System.IdentityModel.Tokens.Jwt;
using Domain.Entities;
using Microsoft.Extensions.Configuration;

namespace Infrastructure.Services;

public class JwtService : IJwtService
{
    private readonly IConfiguration _config;

    public JwtService(IConfiguration config)
    {
        _config = config;
    }

    public string GenerateToken(User user)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_config["Jwt:SecretKey"]!));
        var expireMinutes = int.Parse(_config["Jwt:ExpireInMinute"] ?? "60")!;

        var claims = new[]
        {
            new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()),
            new Claim(ClaimTypes.Email, user.Email!)
        };

        var token = new JwtSecurityToken(
            issuer: _config["Jwt:Issuer"],
            audience: _config["Jwt:Audience"],
            claims: claims,
            expires: DateTime.UtcNow.AddMinutes(expireMinutes),
            signingCredentials: new SigningCredentials(key, SecurityAlgorithms.HmacSha256)
        );

        return new JwtSecurityTokenHandler().WriteToken(token);
    }

    public string GenerateRefreshToken()
    {
        var bytes = new byte[32];
        System.Security.Cryptography.RandomNumberGenerator.Fill(bytes);
        return Convert.ToBase64String(bytes);
    }

    public Result<TokenClaims> ValidateAccessToken(string token)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_config["Jwt:SecretKey"]!));
        var issuer = _config["Jwt:Issuer"]!;
        var audience = _config["Jwt:Audience"]!;
        var handler = new JwtSecurityTokenHandler();

        var validationParams = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = issuer,
            ValidateAudience = true,
            ValidAudience = audience,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = key,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30)
        };

        try
        {
            var principal = handler.ValidateToken(token, validationParams, out _);

            var userId = principal.FindFirstValue(ClaimTypes.NameIdentifier)
                         ?? principal.FindFirstValue(JwtRegisteredClaimNames.Sub);
            var email = principal.FindFirstValue(ClaimTypes.Email)
                         ?? principal.FindFirstValue(JwtRegisteredClaimNames.Email);
            var role = principal.FindFirstValue(ClaimTypes.Role) ?? string.Empty;

            if (userId is null || email is null)
                return Result<TokenClaims>.Failure(Error.Unauthorized("Token is invalid"));

            return Result<TokenClaims>.Success(new TokenClaims(Guid.Parse(userId), email));
        }
        catch (SecurityTokenMalformedException)
        {
            return Result<TokenClaims>.Failure(Error.Unauthorized("Token is malformed."));
        }
        catch (SecurityTokenExpiredException)
        {
            return Result<TokenClaims>.Failure(Error.Unauthorized("Token has expired."));
        }
        catch (SecurityTokenException)
        {
            return Result<TokenClaims>.Failure(Error.Unauthorized("Token is invalid."));
        }
    }
}



