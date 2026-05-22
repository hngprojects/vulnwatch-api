using System.Net;
using System.Security.Cryptography;
using Application.Features.Auth.DTOs;
using Application.Features.Domain.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;


namespace Application.Features.Domain;

public record DeleteDomainCommand(Guid DomainId) : IRequest<Result<MessageResponse>>;

public class DeleteDomainHandler(
    IDomainRepository domains,
    ICurrentUser currentUser,
    ILogger<DeleteDomainHandler> logger
) : IRequestHandler<DeleteDomainCommand, Result<MessageResponse>>
{

    public async Task<Result<MessageResponse>> Handle(DeleteDomainCommand cmd, CancellationToken ct)
    {
        var domain = await domains.GetById(cmd.DomainId, ct);

        if (domain is null)
            return Result<MessageResponse>.Failure(Error.NotFound("Domain not found"));

        if (domain.UserId != currentUser.UserId)
            return Result<MessageResponse>.Failure(Error.Forbidden("You do not have permission to delete this domain"));

        domains.Remove(domain);
        await domains.SaveChangesAsync(ct);

        logger.LogInformation("Domain {DomainId} deleted by user {UserId}", cmd.DomainId, currentUser.UserId);

        return Result<MessageResponse>.Success(new MessageResponse("Domain deleted successfully"));
    }
}