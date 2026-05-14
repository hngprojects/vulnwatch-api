using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Auth;

public record GoogleLoginCommand(string IdToken) : IRequest<Result<AuthResponse>>;

public class GoogleLoginHandler : IRequestHandler<GoogleLoginCommand, Result<AuthResponse>>
{
    private readonly UserManager<User> _userManager;
    private readonly IGoogleTokenVerifier _googleTokenVerifier;
    private readonly IJwtService _jwt;

    public GoogleLoginHandler(
        UserManager<User> userManager,
        IGoogleTokenVerifier googleTokenVerifier,
        IJwtService jwt)
    {
        _userManager = userManager;
        _googleTokenVerifier = googleTokenVerifier;
        _jwt = jwt;
    }

    public async Task<Result<AuthResponse>> Handle(GoogleLoginCommand cmd, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(cmd.IdToken))
            return Result<AuthResponse>.Failure(Error.Validation("Google id token is required."));

        var verificationResult = await _googleTokenVerifier.VerifyIdTokenAsync(cmd.IdToken, ct);
        if (!verificationResult.IsSuccess)
            return Result<AuthResponse>.Failure(verificationResult.Error!);

        var googleUser = verificationResult.Value!;
        if (!googleUser.EmailVerified)
            return Result<AuthResponse>.Failure(Error.Unauthorized("Google account email must be verified."));

        var user = _userManager.Users
            .SingleOrDefault(u => u.GoogleId == googleUser.Subject);

        if (user is null)
        {
            user = await _userManager.FindByEmailAsync(googleUser.Email);

            if (user is null)
            {
                user = User.CreateFromGoogle(googleUser.Email, googleUser.Subject);
                var createResult = await _userManager.CreateAsync(user);

                if (!createResult.Succeeded)
                    return Result<AuthResponse>.Failure(Error.Validation(createResult.Errors.First().Description));
            }
            else
            {
                if (!string.IsNullOrWhiteSpace(user.GoogleId) &&
                    !string.Equals(user.GoogleId, googleUser.Subject, StringComparison.Ordinal))
                {
                    return Result<AuthResponse>.Failure(
                        Error.Conflict("This email is already linked to another Google account."));
                }

                user.LinkGoogleAccount(googleUser.Subject);
                user.ConfirmEmail();
                user.UpdateEmailAddress(googleUser.Email);

                var updateResult = await _userManager.UpdateAsync(user);
                if (!updateResult.Succeeded)
                    return Result<AuthResponse>.Failure(Error.Validation(updateResult.Errors.First().Description));
            }
        }
        else
        {
            var shouldUpdate = user.ConfirmEmail();
            shouldUpdate = user.UpdateEmailAddress(googleUser.Email) || shouldUpdate;

            if (shouldUpdate)
            {
                var updateResult = await _userManager.UpdateAsync(user);
                if (!updateResult.Succeeded)
                    return Result<AuthResponse>.Failure(Error.Validation(updateResult.Errors.First().Description));
            }
        }

        var token = _jwt.GenerateToken(user);
        return Result<AuthResponse>.Success(AuthResponse.Create(token, user));
    }
}
