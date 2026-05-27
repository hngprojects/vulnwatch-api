using Application.Features.Auth.DTOs;
using Application.Features.Integrations.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;

namespace Application.Features.Integrations;

public record ConnectSlackCommand(string Code) : IRequest<Result<MessageResponse>>;

public class ConnectSlackHandler(
    ISlackService slack,
    ISlackIntegrationRepository repo,
    ICurrentUser currentUser)
    : IRequestHandler<ConnectSlackCommand, Result<MessageResponse>>
{
    public async Task<Result<MessageResponse>> Handle(
        ConnectSlackCommand cmd, CancellationToken ct)
    {
        SlackOAuthResult oauthResult;
        try { oauthResult = await slack.ExchangeCodeAsync(cmd.Code, ct); }
        catch (Exception ex)
        {
            return Result<MessageResponse>.Failure(
                Error.Validation($"{ex.Message}"));
        }

        var existing = await repo.GetByUserId(currentUser.UserId, ct);

        if (existing is not null)
        {
            existing.Revoke();
            await repo.SaveChangesAsync(ct);
        }

        var integration = SlackIntegration.Create(
            currentUser.UserId,
            oauthResult.TeamId,
            oauthResult.TeamName,
            oauthResult.ChannelId,
            oauthResult.ChannelName,
            oauthResult.BotAccessToken);

        await repo.AddAsync(integration, ct);
        await repo.SaveChangesAsync(ct);

        return Result<MessageResponse>.Success(
            MessageResponse.Create($"Slack connected to #{oauthResult.ChannelName}"));
    }
}

