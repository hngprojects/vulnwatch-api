using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using MediatR;

namespace Application.Features.Integrations;

public record DisconnectSlackCommand : IRequest<Result<MessageResponse>>;

public class DisconnectSlackHandler(
    ISlackIntegrationRepository repo,
    ICurrentUser currentUser)
    : IRequestHandler<DisconnectSlackCommand, Result<MessageResponse>>
{
    public async Task<Result<MessageResponse>> Handle(
        DisconnectSlackCommand cmd, CancellationToken ct)
    {
        var integration = await repo.GetActiveByUserId(currentUser.UserId, ct);

        if (integration is null)
            return Result<MessageResponse>.Failure(
                Error.NotFound("No active Slack integration found."));

        integration.Revoke();
        await repo.SaveChangesAsync(ct);

        return Result<MessageResponse>.Success(
            MessageResponse.Create("Slack disconnected successfully."));
    }
}