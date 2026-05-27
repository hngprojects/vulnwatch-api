namespace Domain.Entities;

public class SlackIntegration : EntityBase
{
    public Guid UserId { get; private set; }
    public string TeamId { get; private set; } = default!;
    public string TeamName { get; private set; } = default!;
    public string ChannelId { get; private set; } = default!;
    public string ChannelName { get; private set; } = default!;
    public string BotAccessToken { get; private set; } = default!;
    public bool IsActive { get; private set; }
    private SlackIntegration() { }

    public static SlackIntegration Create(
        Guid userId, string teamId, string teamName,
        string channelId, string channelName, string botAccessToken) => new()
    {
        UserId = userId,
        TeamId = teamId,
        TeamName = teamName,
        ChannelId = channelId,
        ChannelName = channelName,
        BotAccessToken = botAccessToken,
        IsActive = true,
    };

    public void Revoke() { IsActive = false; Touch(); }

    public void Activate()
    {
        IsActive = true;
        Touch();
    }

    public void UpdateChannel(string channelId, string channelName)
    {
        ChannelId = channelId;
        ChannelName = channelName;
        Touch();
    }
}