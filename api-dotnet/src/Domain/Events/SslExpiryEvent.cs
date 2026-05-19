namespace Domain.Events;

public record SslExpiryEvent(
    Guid DomainId,
    Guid UserId,
    string DomainName,
    DateTime ExpiresAt,
    int DaysRemaining) : IDomainEvent;