using Application.Helpers;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Identity;


namespace Infrastructure.Services;

public class SessionService : ISessionService
{
    private readonly UserManager<User> _userManager;
    private readonly IJwtService _jwtService;
    private readonly IRefreshTokenRepository _tokenRepo;
    private readonly JwtConfig _config;

    public SessionService(
        UserManager<User> userManager,
        IJwtService jwtService,
        IRefreshTokenRepository tokenRepo,
        IOptions<JwtConfig> config)
    {
        _userManager = userManager;
        _jwtService = jwtService;
        _tokenRepo = tokenRepo;
        _config = config.Value;
    }
    public async Task<Result<AuthTokens>> IssueTokens(
        User user,
        string? ipAddress = null, CancellationToken ct = default)
    {
        var accessToken = _jwtService.GenerateToken(user);
        var refreshStr = _jwtService.GenerateRefreshTokenString();
        var expiresAt = DateTime.UtcNow.AddDays(_config.RefreshTokenExpiryDays);
        var accessExpiry = DateTime.UtcNow.AddMinutes(_config.AccessTokenExpiryMinutes);

        var refreshToken = RefreshToken.Create(user.Id, refreshStr, expiresAt, ipAddress);
        await _tokenRepo.AddAsync(refreshToken, ct);
        await _tokenRepo.SaveChangesAsync(ct);

        return Result<AuthTokens>.Success(new AuthTokens(accessToken, refreshStr, accessExpiry));
    }

    public async Task<Result<AuthTokens>> Refresh(
        string refreshToken, string? ipAddress = null, CancellationToken ct = default)
    {
        var stored = await _tokenRepo.GetByToken(refreshToken, ct);

        if (stored is null)
            return Result<AuthTokens>.Failure(Error.Unauthorized("Invalid token."));

        if (stored.IsRevoked)
            return Result<AuthTokens>.Failure(Error.Unauthorized("Token is revoked."));

        if (stored.IsExpired)
            return Result<AuthTokens>.Failure(Error.Unauthorized("Token is expired."));

        stored.Revoke();

        await _tokenRepo.SaveChangesAsync(ct);

        var user = await _userManager.FindByIdAsync(stored.UserId.ToString());

        if (user is null)
            return Result<AuthTokens>.Failure(Error.Unauthorized("Invalid token."));

        return await IssueTokens(user, ipAddress, ct);
    }

    public async Task<Result<bool>> Revoke(string refreshToken, CancellationToken ct = default)
    {
        var stored = await _tokenRepo.GetByToken(refreshToken, ct);

        if (stored is null)
            return Result<bool>.Failure(Error.Unauthorized("Invalid token."));

        if (stored.IsRevoked)
            return Result<bool>.Success(true);

        stored.Revoke();
        await _tokenRepo.SaveChangesAsync(ct);

        return Result<bool>.Success(true);
    }

    public async Task<Result<bool>> RevokeAll(Guid userId, CancellationToken ct = default)
    {
        var activeTokens = await _tokenRepo.GetActiveByUserId(userId, ct);

        foreach (var token in activeTokens)
            token.Revoke();

        await _tokenRepo.SaveChangesAsync(ct);

        return Result<bool>.Success(true);
    }
}