using Application.Features.Auth.DTOs;
using Application.Helpers;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Options;

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

public class LoginHandler : IRequestHandler<LoginCommand, Result<AuthResponse>>
{
    private readonly UserManager<User> _userManager;
    private readonly IRefreshTokenRepository _refreshTokenRepo;
    private readonly JwtConfig _config;
    private readonly IJwtService _jwt;

    public LoginHandler(UserManager<User> userManager, IRefreshTokenRepository refreshTokenRepo, IOptions<JwtConfig> config, IJwtService jwt)
    {
        _userManager = userManager;
        _refreshTokenRepo = refreshTokenRepo;
        _config = config.Value;
        _jwt = jwt;
    }

    public async Task<Result<AuthResponse>> Handle(LoginCommand cmd, CancellationToken ct)
    {
        var user = await _userManager.FindByEmailAsync(cmd.Email);
        if (user is null || !await _userManager.CheckPasswordAsync(user, cmd.Password))
            return Result<AuthResponse>.Failure(Error.Unauthorized("Invalid email or password."));

        if (!user.EmailConfirmed)
            return Result<AuthResponse>.Failure(Error.Unauthorized("Invalid email or password."));

        var accessToken = _jwt.GenerateToken(user);
        var refreshToken = _jwt.GenerateRefreshToken();

        var refreshTokenExpiryInDays = DateTime.UtcNow.AddDays(_config.RefreshTokenExpiryDays);

        await _refreshTokenRepo.AddAsync(
                RefreshToken.Create(user.Id, refreshToken, refreshTokenExpiryInDays),
                ct);
        await _refreshTokenRepo.SaveChangesAsync(ct);

        return Result<AuthResponse>.Success(AuthResponse.Create(accessToken, refreshToken));
    }
}