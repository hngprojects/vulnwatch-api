using Application.Features.Integrations.DTOs;
using Application.Interfaces;
using Domain.Common;
using MediatR;

namespace Application.Features.Integrations;

public record GetSlackStatusQuery : IRequest<Result<SlackStatusSummary>>;

public class GetSlackStatusHandler(
    ISlackIntegrationRepository repo,
    ICurrentUser currentUser)
    : IRequestHandler<GetSlackStatusQuery, Result<SlackStatusSummary>>
{
    public async Task<Result<SlackStatusSummary>> Handle(
        GetSlackStatusQuery query, CancellationToken ct)
    {
        var integration = await repo.GetActiveByUserId(currentUser.UserId, ct);

        if (integration is null)
            return Result<SlackStatusSummary>.Failure(
                Error.NotFound("No active Slack integration found."));

        return Result<SlackStatusSummary>.Success(
            new SlackStatusSummary(
                Connected: true,
                TeamName: integration.TeamName,
                ChannelName: integration.ChannelName
            ));
    }
}
