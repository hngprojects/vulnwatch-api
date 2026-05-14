using Domain.Common;
using Domain.Entities;

namespace Application.Interfaces;

public record TokenClaims(Guid UserId, string Email);

public interface IJwtService
{
    string GenerateToken(User user);

    Result<TokenClaims> ValidateAccessToken(string token);

    string GenerateRefreshToken();
}
