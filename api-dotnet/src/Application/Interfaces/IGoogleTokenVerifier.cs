using Domain.Common;

namespace Application.Interfaces;

public interface IGoogleTokenVerifier
{
    Task<Result<GoogleUserInfo>> VerifyIdTokenAsync(string idToken, CancellationToken ct);
}

// public sealed record GoogleUserInfo(string Subject, string Email, bool EmailVerified);

public sealed record GoogleUserInfo(string Subject, string Email, bool EmailVerified, string? Name, string? Picture);
