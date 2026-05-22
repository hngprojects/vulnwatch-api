using Application.Features.Auth.DTOs;
using Application.Features.Profile.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Profile;

public record DeleteAccountCommand : IRequest<Result<MessageResponse>>;

public class DeleteAccountHandler(
    UserManager<User> userManager,
    // IScanRepository scanRepo,
    // IDomainRepository domainRepo,
    ICurrentUser currentUser)
    : IRequestHandler<DeleteAccountCommand, Result<MessageResponse>>
{
    public async Task<Result<MessageResponse>> Handle(DeleteAccountCommand cmd, CancellationToken ct)
    {
        var user = await userManager.FindByIdAsync(currentUser.UserId.ToString());

        if (user is null)
            return Result<MessageResponse>.Failure(Error.NotFound("User not found."));

        // Cascade-delete owned data before removing the Identity record.
        // If your FK constraints handle this via ON DELETE CASCADE,
        // you can remove these lines — but explicit is safer.
        // await scanRepo.DeleteAllForUserAsync(currentUser.UserId, ct);
        // await domainRepo.DeleteAllForUserAsync(currentUser.UserId, ct);

        var result = await userManager.DeleteAsync(user);

        if (!result.Succeeded)
        {
            var error = result.Errors.First().Description;
            return Result<MessageResponse>.Failure(Error.Validation(error));
        }

        return Result<MessageResponse>.Success(MessageResponse.Create("Account deleted successfully."));
    }
}