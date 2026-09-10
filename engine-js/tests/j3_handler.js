var count = 0;

function page() {
  return ui.app({ title: "Counter" },
    ui.column({ spacing: 8 },
      ui.text({ text: "count = " + count, size: 20 }),
      ui.button({
        text: "+1",
        onClick: function () {
          count++;
          print("clicked, count=" + count);
          return page();
        }
      })
    )
  );
}

return page();
