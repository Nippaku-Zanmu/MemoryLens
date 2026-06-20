package com.memorylens.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.memorylens.app.databinding.ActivityEnrolledPersonsBinding
import com.memorylens.app.storage.AppDatabase
import com.memorylens.app.storage.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists all enrolled persons and allows deletion.
 */
class EnrolledPersonsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnrolledPersonsBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private val adapter = PersonAdapter(::onDeletePerson)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrolledPersonsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerPersons.layoutManager = LinearLayoutManager(this)
        binding.recyclerPersons.adapter = adapter

        lifecycleScope.launch {
            db.personDao().getAllPersonsFlow().collectLatest { persons ->
                adapter.submitList(persons)
            }
        }
    }

    private fun onDeletePerson(person: PersonEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${person.name}?")
            .setMessage("This will remove their face data and all conversation history.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { withContext(Dispatchers.IO) { db.personDao().delete(person) } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

private class PersonAdapter(
    private val onDelete: (PersonEntity) -> Unit
) : RecyclerView.Adapter<PersonAdapter.VH>() {

    private var items: List<PersonEntity> = emptyList()

    fun submitList(list: List<PersonEntity>) {
        items = list; notifyDataSetChanged()
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
    ) {
        val tvName: TextView = itemView.findViewById(android.R.id.text1)
        val tvDetail: TextView = itemView.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.tvName.text = p.name
        val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(p.enrolledAt))
        holder.tvDetail.text = "${p.relationship} · Enrolled $dateStr"
        holder.itemView.setOnLongClickListener { onDelete(p); true }
    }

    override fun getItemCount() = items.size
}
