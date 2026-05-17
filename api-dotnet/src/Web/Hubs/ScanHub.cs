using Microsoft.AspNetCore.SignalR;

namespace Web.Hubs;

public class ScanHub : Hub
{
    // clients join a group by userId so results are scoped
    public async Task JoinUserGroup(string userId) =>
        await Groups.AddToGroupAsync(Context.ConnectionId, $"user:{userId}");
}