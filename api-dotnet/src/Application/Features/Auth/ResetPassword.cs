using System.Net;
using Application.Features.Auth.DTOs;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Auth;

public record ResetPasswordCommand(string Email, string Token, string NewPassword) : IRequest<Result<MessageResponse>>;

public class ResetPasswordHandler : IRequestHandler<ResetPasswordCommand, Result<MessageResponse>>
{
    private readonly UserManager<User> _userManager;

    public ResetPasswordHandler(UserManager<User> userManager) => _userManager = userManager;

    public async Task<Result<MessageResponse>> Handle(ResetPasswordCommand cmd, CancellationToken ct)
    {
        var user = await _userManager.FindByEmailAsync(cmd.Email);
        if (user is null)
            return Result<MessageResponse>.Failure(Error.NotFound("User not found."));

        var decodedToken = WebUtility.UrlDecode(cmd.Token);

        var result = await _userManager.ResetPasswordAsync(user, decodedToken, cmd.NewPassword);
        if (!result.Succeeded)
            return Result<MessageResponse>.Failure(Error.Validation(result.Errors.First().Description));

        return Result<MessageResponse>.Success(MessageResponse.Create("Password reset successfully."));
    }
}
