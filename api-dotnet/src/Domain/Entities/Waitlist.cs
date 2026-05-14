namespace Domain.Entities;

public class Waitlist : EntityBase
{
    public string Email { get; private set; } = default!;
    public string? CompanyName { get; private set; }

    private Waitlist() { }

    public static Waitlist Create(string email, string? companyName = null)
        => new()
        {
            Email = email,
            CompanyName = companyName,
        };
}
