

using Domain.Common;
using Domain.Entities;

namespace Application.Interfaces;

public record AuthTokens(string AccessToken, string RefreshToken, DateTime AccessTokenExpiresAt);

public interface ISessionService
{
    Task<Result<AuthTokens>> IssueTokens(User user,
        string? ipAddress = null, CancellationToken ct = default);

    Task<Result<AuthTokens>> Refresh(string refreshToken, string? ipAddress = null,
        CancellationToken ct = default);

    Task<Result<bool>> Revoke(string refreshToken, CancellationToken ct = default);

    Task<Result<bool>> RevokeAll(Guid userId, CancellationToken ct = default);
}
