using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;

namespace Application.Features.Auth;

public record LoginCommand(string Email, string Password) : IRequest<Result<AuthResponse>>;

public class LoginCommandValidator : AbstractValidator<LoginCommand>
{
    public LoginCommandValidator()
    {
        RuleFor(x => x.Email)
            .NotEmpty().WithMessage("Email is required.")
            .EmailAddress().WithMessage("Email must be a valid email address.");

        RuleFor(x => x.Password)
            .NotEmpty().WithMessage("Password is required.");
    }
}

public class LoginHandler(
    UserManager<User> userManager,
    IRefreshTokenRepository refreshTokenRepo,
    IConfiguration config,
    IJwtService jwt) : IRequestHandler<LoginCommand, Result<AuthResponse>>
{

    public async Task<Result<AuthResponse>> Handle(LoginCommand cmd, CancellationToken ct)
    {
        var user = await userManager.FindByEmailAsync(cmd.Email);
        if (user is null || !await userManager.CheckPasswordAsync(user, cmd.Password))
            return Result<AuthResponse>.Failure(Error.Unauthorized("Invalid email or password."));

        if (!user.EmailConfirmed)
            return Result<AuthResponse>.Failure(Error.Forbidden("Your account has not been verified."));

        var accessToken = jwt.GenerateToken(user);
        var refreshToken = jwt.GenerateRefreshToken();

        var refreshTokenExpiryInDays = DateTime.UtcNow.AddMinutes(int.Parse(config["Jwt:RefreshTokenExpiryDays"]!));

        await refreshTokenRepo.AddAsync(
                RefreshToken.Create(user.Id, refreshToken, refreshTokenExpiryInDays),
                ct);
        await refreshTokenRepo.SaveChangesAsync(ct);

        return Result<AuthResponse>.Success(AuthResponse.Create(accessToken, refreshToken));
    }
}