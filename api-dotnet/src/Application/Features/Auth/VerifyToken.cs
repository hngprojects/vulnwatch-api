using System.Net;
using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Auth;

public record VerifyTokenCommand(string UserId, string Token) : IRequest<Result<MessageResponse>>;

public class VerifyTokenHandler : IRequestHandler<VerifyTokenCommand, Result<MessageResponse>>
{
    private readonly UserManager<User> _userManager;
    private readonly IJwtService _jwt;

    public VerifyTokenHandler(UserManager<User> userManager, IJwtService jwt)
    {
        _userManager = userManager;
        _jwt = jwt;
    }

    public async Task<Result<MessageResponse>> Handle(VerifyTokenCommand cmd, CancellationToken ct)
    {
        var user = await _userManager.FindByIdAsync(cmd.UserId);
        if (user is null)
            return Result<MessageResponse>.Failure(Error.NotFound("User not found."));

        if (user.EmailConfirmed)
        {
            var existingToken = _jwt.GenerateToken(user);
            return Result<MessageResponse>.Success(MessageResponse.Create("Email verified! Proceed to login."));
        }

        var decodedToken = WebUtility.UrlDecode(cmd.Token);

        var identityResult = await _userManager.ConfirmEmailAsync(user, decodedToken);

        if (!identityResult.Succeeded)
            return Result<MessageResponse>.Failure(
                Error.Validation(identityResult.Errors.First().Description));

        user.Activate();
        await _userManager.UpdateAsync(user);

        var token = _jwt.GenerateToken(user);
        return Result<MessageResponse>.Success(MessageResponse.Create("Email verified! Proceed to login."));
    }
}