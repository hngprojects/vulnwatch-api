using System.Net;
using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Auth;

public record ChangePasswordCommand(
    string CurrentPassword,
    string NewPassword,
    string ConfirmNewPassword) : IRequest<Result<MessageResponse>>;

public class ChangePasswordHandler(
    UserManager<User> userManager,
    ICurrentUser currentUser)
    : IRequestHandler<ChangePasswordCommand, Result<MessageResponse>>
{
    public async Task<Result<MessageResponse>> Handle(ChangePasswordCommand cmd, CancellationToken ct)
    {
        if (cmd.NewPassword != cmd.ConfirmNewPassword)
            return Result<MessageResponse>.Failure(
                Error.Validation("New password and confirmation do not match."));

        if (cmd.CurrentPassword == cmd.NewPassword)
            return Result<MessageResponse>.Failure(
                Error.Validation("New password must be different from your current password."));

        var user = await userManager.FindByIdAsync(currentUser.UserId.ToString());
        if (user is null)
            return Result<MessageResponse>.Failure(Error.NotFound("User not found."));

        // Guard: Google-only accounts have no password set
        var hasPassword = await userManager.HasPasswordAsync(user);
        if (!hasPassword)
            return Result<MessageResponse>.Failure(
                Error.Validation("Your account uses Google sign-in. Please set a password first."));

        var result = await userManager.ChangePasswordAsync(user, cmd.CurrentPassword, cmd.NewPassword);

        if (!result.Succeeded)
            return Result<MessageResponse>.Failure(
                Error.Validation(result.Errors.First().Description));

        return Result<MessageResponse>.Success(
            MessageResponse.Create("Password changed successfully."));
    }
}