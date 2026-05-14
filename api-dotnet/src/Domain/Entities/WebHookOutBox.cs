using Domain.Enums;

namespace Domain.Entities;

public class WebHookOutBox : EntityBase
{
    public string MessageBody { get; private set; } = default!;
    public int NumRetries { get; private set; }
    public OutboxStatus Status { get; private set; }
    public DateTime? DeliveredAt { get; private set; }

    private WebHookOutBox() { }

    public static WebHookOutBox Create(string messageBody)
        => new()
        {
            MessageBody = messageBody,
            Status = OutboxStatus.Pending,
        };

    public void MarkDelivered()
    {
        Status = OutboxStatus.Delivered;
        DeliveredAt = DateTime.UtcNow;
        Touch();
    }

    public void MarkDeadLetter()
    {
        Status = OutboxStatus.DeadLetter;
        Touch();
    }

    public void IncrementRetry()
    {
        NumRetries++;
        Touch();
    }
}
