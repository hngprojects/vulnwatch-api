using Domain.Enums;

namespace Domain.Events;

public record ScanCompletedEvent(
    Guid ScanId,
    Guid DomainId,
    Guid UserId,
    string DomainName,
    int SecurityScore,
    List<FindingSeverity> FindingSeverities) : IDomainEvent;
