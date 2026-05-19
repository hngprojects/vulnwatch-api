using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddMissingQueryIndexes : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_Remediations_FindingId",
                table: "Remediations");

            migrationBuilder.DropIndex(
                name: "IX_NotificationPreferences_UserId",
                table: "NotificationPreferences");

            migrationBuilder.DropIndex(
                name: "IX_Integrations_UserId",
                table: "Integrations");

            migrationBuilder.DropIndex(
                name: "IX_Findings_ScanId",
                table: "Findings");

            migrationBuilder.AlterColumn<string>(
                name: "Status",
                table: "Alerts",
                type: "text",
                nullable: false,
                oldClrType: typeof(int),
                oldType: "integer");

            migrationBuilder.CreateIndex(
                name: "IX_WebHookOutBox_Pending_CreatedAt",
                table: "WebHookOutBox",
                columns: new[] { "Status", "CreatedAt" },
                filter: "\"Status\" = 'Pending'");

            migrationBuilder.CreateIndex(
                name: "IX_Remediations_FindingId_Status",
                table: "Remediations",
                columns: new[] { "FindingId", "Status" });

            migrationBuilder.CreateIndex(
                name: "IX_NotificationPreferences_UserId",
                table: "NotificationPreferences",
                column: "UserId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Integrations_UserId_Status",
                table: "Integrations",
                columns: new[] { "UserId", "Status" });

            migrationBuilder.CreateIndex(
                name: "IX_Findings_ScanId_Severity_Status",
                table: "Findings",
                columns: new[] { "ScanId", "Severity", "Status" });

            migrationBuilder.CreateIndex(
                name: "IX_Alerts_Pending_Channel_CreatedAt",
                table: "Alerts",
                columns: new[] { "Channel", "CreatedAt" },
                filter: "\"Status\" = 'Pending'");

            migrationBuilder.CreateIndex(
                name: "IX_Alerts_UserId_CreatedAt",
                table: "Alerts",
                columns: new[] { "UserId", "CreatedAt" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_WebHookOutBox_Pending_CreatedAt",
                table: "WebHookOutBox");

            migrationBuilder.DropIndex(
                name: "IX_Remediations_FindingId_Status",
                table: "Remediations");

            migrationBuilder.DropIndex(
                name: "IX_NotificationPreferences_UserId",
                table: "NotificationPreferences");

            migrationBuilder.DropIndex(
                name: "IX_Integrations_UserId_Status",
                table: "Integrations");

            migrationBuilder.DropIndex(
                name: "IX_Findings_ScanId_Severity_Status",
                table: "Findings");

            migrationBuilder.DropIndex(
                name: "IX_Alerts_Pending_Channel_CreatedAt",
                table: "Alerts");

            migrationBuilder.DropIndex(
                name: "IX_Alerts_UserId_CreatedAt",
                table: "Alerts");

            migrationBuilder.AlterColumn<int>(
                name: "Status",
                table: "Alerts",
                type: "integer",
                nullable: false,
                oldClrType: typeof(string),
                oldType: "text");

            migrationBuilder.CreateIndex(
                name: "IX_Remediations_FindingId",
                table: "Remediations",
                column: "FindingId");

            migrationBuilder.CreateIndex(
                name: "IX_NotificationPreferences_UserId",
                table: "NotificationPreferences",
                column: "UserId");

            migrationBuilder.CreateIndex(
                name: "IX_Integrations_UserId",
                table: "Integrations",
                column: "UserId");

            migrationBuilder.CreateIndex(
                name: "IX_Findings_ScanId",
                table: "Findings",
                column: "ScanId");
        }
    }
}
